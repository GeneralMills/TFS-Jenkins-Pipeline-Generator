package tfsbranchsourceplugin.tfs_branch_source;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.UserCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.squareup.okhttp.*;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.json.JSONArray;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MultiBranchPipelineBuilder extends Builder implements SimpleBuildStep {

    public final String teamProjectUrl;
    public final String credentialsId;
    public final Boolean runPipelines;

    @DataBoundConstructor
    public MultiBranchPipelineBuilder(String teamProjectUrl, String credentialsId, Boolean runPipelines) {
        this.teamProjectUrl = teamProjectUrl;
        this.credentialsId = credentialsId;
        this.runPipelines = runPipelines;
    }

    @Override
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {

        String url = this.teamProjectUrl;
        String file = "Jenkinsfile";
        String credentials = credentialsId;

        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        final StandardUsernamePasswordCredentials tfsCredentials;
        try {
            tfsCredentials = getStandardUsernamePasswordCredentials(credentials);
        } catch (Exception e) {
            listener.getLogger().println(e.getMessage());
            return;
        }
        ArrayList<String> reposWithFile = new ArrayList<>();

        OkHttpClient client = new OkHttpClient();

        listener.getLogger().println("--Looking through Team Project for repos--");
        JSONArray repos = getReposForTeamProject(listener, client, url, tfsCredentials);

        for (int i = 0; i < repos.length(); i++) {
            listener.getLogger().println("\n\n--Looking through repo for branches--");
            JSONArray branches = getBranchesForRepo(listener, client, url, repos.getJSONObject(i), tfsCredentials);

            for (int j = 0; j < branches.length(); j++) {
                if (checkBranchesForFile(listener, client, url, repos.getJSONObject(i).get("id").toString(), branches.getJSONObject(j), file, tfsCredentials)) {
                    //Once we have found the file we are looking for, we don't need to check the rest of the branches
                    reposWithFile.add(repos.getJSONObject(i).get("name").toString());
                    break;
                }
            }


            //Create one-off pipeline jobs if they have files in the top level of their master branch that are .Jenkinsfiles
            List<String> jenkinsfiles = getJenkinsfileTypesFromMasterBranch(listener, client, url, repos.getJSONObject(i), tfsCredentials);
            if(jenkinsfiles != null && jenkinsfiles.size() > 0) {
                listener.getLogger().printf("\t--Creating Pipeline jobs from .Jenkinsfiles--%n");
                String folderName = ((FreeStyleBuild) build).getProject().getParent().getFullName();
                Folder folder = Jenkins.getInstance().getItemByFullName(folderName, Folder.class);
                for(String jenkinsfile : jenkinsfiles) {
                    String repoName = repos.getJSONObject(i).get("name").toString();
                    String jobName = String.format("%s %s", repoName, jenkinsfile.split("\\.")[0]);
                    TopLevelItem job = folder.getItem(jobName);
                    if (job == null) {
                        InputStream configuredFile = replaceTokensInXML(getPipelineXml(), repos.getJSONObject(i).get("name").toString(), credentials, url, jenkinsfile);
                        folder.createProjectFromXML(jobName, configuredFile);
                        listener.getLogger().printf("\tCreated pipeline for: %s%n", jobName);
                    } else {
                        listener.getLogger().printf("\t%s pipeline already exists%n", jobName);
                    }
                }
            }
            else {
                listener.getLogger().printf("\tNo .Jenkinsfiles found in master branch");
            }
        }

        listener.getLogger().println("\n\n--Processing repos--");
        String folderName = ((FreeStyleBuild) build).getProject().getParent().getFullName();
        Folder folder = Jenkins.getInstance().getItemByFullName(folderName, Folder.class);
        if (folder == null) {
            throw new AbortException(String.format("Team project folder does not exist! Please make a folder for the %s team project before running this job.", folderName));
        }
        else {
            for (String name : reposWithFile) {
                InputStream configuredFile = replaceTokensInXML(getMultibranchPipelineXml(), name, credentials, url, file);
                TopLevelItem job = folder.getItem(name);
                if (job == null) {
                    folder.createProjectFromXML(name, configuredFile);
                    listener.getLogger().println("Created multibranch pipeline for: " + name);
                    WorkflowMultiBranchProject mbp = (WorkflowMultiBranchProject) folder.getItem(name);
                    if(runPipelines) {
                        mbp.scheduleBuild();
                    }
                } else {
                    listener.getLogger().println(name + " multibranch pipeline already exists");
                }
            }
        }
    }

    @Override
    public MultiBranchPipelineBuilder.DescriptorImpl getDescriptor() {
        return (MultiBranchPipelineBuilder.DescriptorImpl) super.getDescriptor();
    }

    private JSONArray getReposForTeamProject(TaskListener listener, OkHttpClient client, String teamProjectUrl, StandardUsernamePasswordCredentials tfsCredentials) throws IOException {
        String listOfReposUrl = teamProjectUrl + "/_apis/git/repositories?api-version=1";
        org.json.JSONObject obj = callGet(client, listOfReposUrl, tfsCredentials);
        JSONArray repos = obj.getJSONArray("value");
        for (int i = 0; i < repos.length(); i++) {
            org.json.JSONObject repo = repos.getJSONObject(i);
            listener.getLogger().println("Found repo: - " + repo.get("id") + " - " + repo.get("name"));
        }
        return repos;
    }

    private JSONArray getBranchesForRepo(TaskListener listener, OkHttpClient client, String teamProjectUrl, org.json.JSONObject repo, StandardUsernamePasswordCredentials tfsCredentials) throws IOException {
        String listOfBranchesUrl = String.format("%s/_apis/git/repositories/%s/refs?filter=heads&api-version=1.0", teamProjectUrl, repo.get("id"));
        org.json.JSONObject obj = callGet(client, listOfBranchesUrl, tfsCredentials);
        JSONArray branches = obj.getJSONArray("value");
        listener.getLogger().println("Repo: " + repo.get("name"));
        for (int j = 0; j < branches.length(); j++) {
            org.json.JSONObject branch = branches.getJSONObject(j);
            listener.getLogger().println("Found branch: " + branch.get("name"));
        }

        return branches;
    }

    private boolean checkBranchesForFile(TaskListener listener, OkHttpClient client, String teamProjectUrl, String repoId, org.json.JSONObject branch, String file, StandardUsernamePasswordCredentials tfsCredentials) throws IOException {
        String branchName = branch.get("name").toString().substring(11);
        listener.getLogger().printf("\t--Looking through branch: %s for a jenkinsfile--%n", branchName);
        String jenkinsFileMetadataUrl = String.format("%s/_apis/git/repositories/%s/items?api-version=1.0&version=%s&scopepath=/%s", teamProjectUrl, repoId, branchName, file);
        org.json.JSONObject obj = callGet(client, jenkinsFileMetadataUrl, tfsCredentials);
        if (obj.has("value")) {
            JSONArray fileList = obj.getJSONArray("value");
            for (int k = 0; k < fileList.length(); k++) {
                listener.getLogger().println("\tFOUND JENKINS FILE!");
                return true;
            }
        } else {
            listener.getLogger().println("\tNone found");
            return false;
        }
        return false;
    }

    private List<String> getJenkinsfileTypesFromMasterBranch(TaskListener listener, OkHttpClient client, String teamProjectUrl, org.json.JSONObject repo, StandardUsernamePasswordCredentials tfsCredentials) throws IOException{
        List<String> jenkinsfiles = new ArrayList<String>();
        listener.getLogger().println("\t--Searching for master branch--");
        String listOfFiles = String.format("%s/_apis/git/repositories/%s/items?api-version-1.0&version=master&scopepath=/&recursionLevel=OneLevel", teamProjectUrl, repo.get("id"));
        org.json.JSONObject filesJson = callGet(client, listOfFiles, tfsCredentials);
        if (filesJson.has("value")) {
            JSONArray files = filesJson.getJSONArray("value");
            for (int j = 0; j < files.length(); j++) {
                org.json.JSONObject file = files.getJSONObject(j);
                if(file.get("path").toString().endsWith(".Jenkinsfile")) {
                    jenkinsfiles.add(file.get("path").toString().substring(1));
                }
            }
            return jenkinsfiles;
        }
        else {
            listener.getLogger().println("\tRepo has no master branch");
            return null;
        }
    }

    private org.json.JSONObject callGet(OkHttpClient client, String url, StandardUsernamePasswordCredentials tfsCredentials) throws IOException {
        Request okRequest = new Request.Builder()
                .url(url)
                .addHeader("content-type", "application/json")
                .addHeader("accept", "application/json")
                .addHeader("authorization", Credentials.basic(tfsCredentials.getUsername(), tfsCredentials.getPassword().getPlainText()))
                .build();
        Response response = client.newCall(okRequest).execute();

        String json = response.body().string();
        response.body().close();
        return new org.json.JSONObject(json);
    }

    private StandardUsernamePasswordCredentials getStandardUsernamePasswordCredentials(String credentialsId) throws Exception {
        ClassLoader loader = Jenkins.getInstance().pluginManager.getPlugin("credentials").classLoader;

        UserCredentialsProvider provider = new UserCredentialsProvider();
        Class credentialType = loader.loadClass("com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials");
        List<StandardUsernamePasswordCredentials> credentials = provider.lookupCredentials(credentialType);

        StandardUsernamePasswordCredentials tfsCredentials = null;
        for (StandardUsernamePasswordCredentials c : credentials) {
            if (c.getId().equals(credentialsId)) {
                tfsCredentials = c;
            }
        }
        if (tfsCredentials == null) {
            throw new Exception("No TFS username found");
        }
        return tfsCredentials;
    }

    private InputStream replaceTokensInXML(String xml, String repoName, String credentialsId, String url, String file) throws IOException {
        String repoToken = "#repo#";
        String guidToken = "#guid#";
        String credentialsToken = "#credentialsId#";
        String urlToken = "#url#";
        String fileToken = "#fileType#";

        xml = xml.replaceAll(repoToken, repoName);
        xml = xml.replaceAll(guidToken, java.util.UUID.randomUUID().toString());
        xml = xml.replaceAll(credentialsToken, credentialsId);
        xml = xml.replaceAll(urlToken, url);
        xml = xml.replaceAll(fileToken, file);

        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    private String getMultibranchPipelineXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject plugin=\"workflow-multibranch@2.16\">\n" +
                "    <actions>\n" +
                "        <io.jenkins.blueocean.service.embedded.BlueOceanUrlAction plugin=\"blueocean-rest-impl@1.1.2\">\n" +
                "            <blueOceanUrlObject class=\"io.jenkins.blueocean.service.embedded.BlueOceanUrlObjectImpl\">\n" +
                "            </blueOceanUrlObject>\n" +
                "        </io.jenkins.blueocean.service.embedded.BlueOceanUrlAction>\n" +
                "    </actions>\n" +
                "    <description />\n" +
                "    <properties>\n" +
                "        <com.cloudbees.hudson.plugins.folder.properties.EnvVarsFolderProperty plugin=\"cloudbees-folders-plus@3.1\">\n" +
                "            <properties />\n" +
                "        </com.cloudbees.hudson.plugins.folder.properties.EnvVarsFolderProperty>\n" +
                "        <org.jenkinsci.plugins.pipeline.modeldefinition.config.FolderConfig plugin=\"pipeline-model-definition@1.1.7\">\n" +
                "            <dockerLabel />\n" +
                "            <registry plugin=\"docker-commons@1.7\" />\n" +
                "        </org.jenkinsci.plugins.pipeline.modeldefinition.config.FolderConfig>\n" +
                "    </properties>\n" +
                "    <folderViews class=\"jenkins.branch.MultiBranchProjectViewHolder\" plugin=\"branch-api@2.0.10\">\n" +
                "        <owner class=\"org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject\" reference=\"../..\" />\n" +
                "    </folderViews>\n" +
                "    <healthMetrics>\n" +
                "        <com.cloudbees.hudson.plugins.folder.health.WorstChildHealthMetric plugin=\"cloudbees-folder@6.1.2\">\n" +
                "            <nonRecursive>false</nonRecursive>\n" +
                "        </com.cloudbees.hudson.plugins.folder.health.WorstChildHealthMetric>\n" +
                "    </healthMetrics>\n" +
                "    <icon class=\"jenkins.branch.MetadataActionFolderIcon\" plugin=\"branch-api@2.0.10\">\n" +
                "        <owner class=\"org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject\" reference=\"../..\" />\n" +
                "    </icon>\n" +
                "    <orphanedItemStrategy class=\"com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy\" plugin=\"cloudbees-folder@6.1.2\">\n" +
                "        <pruneDeadBranches>true</pruneDeadBranches>\n" +
                "        <daysToKeep>1</daysToKeep>\n" +
                "        <numToKeep>1</numToKeep>\n" +
                "    </orphanedItemStrategy>\n" +
                "    <triggers>\n" +
                "    <com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger plugin=\"cloudbees-folder@6.9\">\n" +
                "    <spec>H H/4 * * *</spec>\n" +
                "    <interval>86400000</interval>\n" +
                "    </com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger>\n" +
                "    </triggers>\n" +
                "    <disabled>false</disabled>\n" +
                "    <sources class=\"jenkins.branch.MultiBranchProject$BranchSourceList\" plugin=\"branch-api@2.0.10\">\n" +
                "        <data>\n" +
                "            <jenkins.branch.BranchSource>\n" +
                "                <source class=\"jenkins.plugins.git.GitSCMSource\" plugin=\"git@3.3.0\">\n" +
                "                    <id>#guid#</id>\n" +
                "                    <remote>#url#/_git/#repo#</remote>\n" +
                "                    <credentialsId>#credentialsId#</credentialsId>\n" +
                "                    <remoteName>origin</remoteName>\n" +
                "                    <rawRefSpecs>+refs/heads/*:refs/remotes/origin/*</rawRefSpecs>\n" +
                "                    <includes>*</includes>\n" +
                "                    <excludes />\n" +
                "                    <ignoreOnPushNotifications>false</ignoreOnPushNotifications>\n" +
                "                    <browser class=\"hudson.plugins.git.browser.TFS2013GitRepositoryBrowser\">\n" +
                "                        <url />\n" +
                "                    </browser>\n" +
                "                    <gitTool>Default</gitTool>\n" +
                "                </source>\n" +
                "                <strategy class=\"jenkins.branch.DefaultBranchPropertyStrategy\">\n" +
                "                    <properties class=\"empty-list\" />\n" +
                "                </strategy>\n" +
                "            </jenkins.branch.BranchSource>\n" +
                "        </data>\n" +
                "        <owner class=\"org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject\" reference=\"../..\" />\n" +
                "    </sources>\n" +
                "    <factory class=\"org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory\">\n" +
                "        <owner class=\"org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject\" reference=\"../..\" />\n" +
                "        <scriptPath>#fileType#</scriptPath>\n" +
                "    </factory>\n" +
                "</org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject>";
    }

    private String getPipelineXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<flow-definition plugin=\"workflow-job@2.17\">\n" +
                "    <description />\n" +
                "    <keepDependencies>false</keepDependencies>\n" +
                "    <properties>\n" +
                "        <io.fabric8.jenkins.openshiftsync.BuildConfigProjectProperty plugin=\"openshift-sync@1.0.9\">\n" +
                "            <uid />\n" +
                "            <namespace />\n" +
                "            <name />\n" +
                "            <resourceVersion />\n" +
                "        </io.fabric8.jenkins.openshiftsync.BuildConfigProjectProperty>\n" +
                "        <com.synopsys.arc.jenkinsci.plugins.jobrestrictions.jobs.JobRestrictionProperty plugin=\"job-restrictions@0.6\" />\n" +
                "    </properties>\n" +
                "    <definition class=\"org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition\" plugin=\"workflow-cps@2.43\">\n" +
                "        <scm class=\"hudson.plugins.git.GitSCM\" plugin=\"git@3.6.3\">\n" +
                "            <configVersion>2</configVersion>\n" +
                "            <userRemoteConfigs>\n" +
                "                <hudson.plugins.git.UserRemoteConfig>\n" +
                "                    <url>#url#/_git/#repo#</url>\n" +
                "                    <credentialsId>#credentialsId#</credentialsId>\n" +
                "                </hudson.plugins.git.UserRemoteConfig>\n" +
                "            </userRemoteConfigs>\n" +
                "            <branches>\n" +
                "                <hudson.plugins.git.BranchSpec>\n" +
                "                    <name>*/master</name>\n" +
                "                </hudson.plugins.git.BranchSpec>\n" +
                "            </branches>\n" +
                "            <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>\n" +
                "            <gitTool>Default</gitTool>\n" +
                "            <submoduleCfg class=\"list\" />\n" +
                "            <extensions />\n" +
                "        </scm>\n" +
                "        <scriptPath>#fileType#</scriptPath>\n" +
                "        <lightweight>true</lightweight>\n" +
                "    </definition>\n" +
                "    <triggers />\n" +
                "    <disabled>false</disabled>\n" +
                "</flow-definition>";
    }

    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public DescriptorImpl() {
            load();
        }


        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item context,
                                                     @QueryParameter String remote,
                                                     @QueryParameter String username) {
            if (context == null && !Jenkins.getActiveInstance().hasPermission(Jenkins.ADMINISTER) ||
                    context != null && !context.hasPermission(Item.EXTENDED_READ)) {
                return new StandardListBoxModel().includeCurrentValue(username);
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            context instanceof Queue.Task ? Tasks.getAuthenticationOf((Queue.Task) context) : ACL.SYSTEM,
                            context,
                            StandardUsernameCredentials.class,
                            URIRequirementBuilder.fromUri(remote).build(),
                            GitClient.CREDENTIALS_MATCHER)
                    .includeCurrentValue(username);
        }

        public String getDisplayName() {
            return "Generate MultiBranch pipeline from TFS GIT";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return super.configure(req, formData);
        }
    }
}
