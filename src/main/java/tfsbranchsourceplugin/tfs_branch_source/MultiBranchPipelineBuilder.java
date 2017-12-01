package tfsbranchsourceplugin.tfs_branch_source;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.UserCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.squareup.okhttp.Authenticator;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
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
import org.json.JSONArray;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.*;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MultiBranchPipelineBuilder extends Builder implements SimpleBuildStep {

    private final String teamProjectUrl;
    private final String credentialsId;
    private final String projectRecognizer;


    @DataBoundConstructor
    public MultiBranchPipelineBuilder(String teamProjectUrl, String credentialsId, String projectRecognizer) {
        this.teamProjectUrl = teamProjectUrl;
        this.credentialsId = credentialsId;
        this.projectRecognizer = projectRecognizer;

    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @Override
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {

        String url = this.teamProjectUrl;
        String file = projectRecognizer;
        String credentials = credentialsId;

        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        //Get the team project name which will also be the folder name this is under
        String team = url.substring(url.lastIndexOf('/') + 1);

        final StandardUsernamePasswordCredentials tfsCredentials;
        try {
            tfsCredentials = getStandardUsernamePasswordCredentials(credentials);
        } catch (Exception e) {
            listener.getLogger().println(e.getMessage());
            return;
        }
        ArrayList<String> reposWithFile = new ArrayList<>();

        OkHttpClient client = createClient(tfsCredentials);

        listener.getLogger().println("--Looking through Team Project for repos--");
        JSONArray repos = GetReposForTeamProject(listener, client, url);

        for (int i = 0; i < repos.length(); i++) {
            listener.getLogger().println("\n\n--Looking through repo for branches--");
            JSONArray branches = GetBranchesForRepo(listener, client, url, repos.getJSONObject(i));

            for (int j = 0; j < branches.length(); j++) {
                if (checkBranchesForFile(listener, client, url, repos.getJSONObject(i).get("id").toString(), branches.getJSONObject(j), file)) {
                    //Once we have found the file we are looking for, we don't need to check the rest of the branches
                    reposWithFile.add(repos.getJSONObject(i).get("name").toString());
                    break;
                }
            }
        }

        listener.getLogger().println("\n\n--Processing repos--");
        Folder folder = Jenkins.getInstance().getItemByFullName(team, Folder.class);
        if (folder == null) {
            throw new AbortException("Team project folder does not exist!  Please make a folder for the " + team + " team project before running this job.");
        } else {
            for (String name : reposWithFile) {
                File xmlFile = new File("C:\\Projects\\GMITFSPlugin\\tfs_vsts_branch_source\\xml\\config.xml");
                InputStream foobar = replaceTokensInXML(xmlFile, name, credentials, url, file, team);
                TopLevelItem job = folder.getItem(name);
                if (job == null) {
                    folder.createProjectFromXML(name, foobar);
                    listener.getLogger().println("Created multibranch pipeline for: " + name);
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

    private JSONArray GetReposForTeamProject(TaskListener listener, OkHttpClient client, String teamProjectUrl) throws IOException {
        String listOfReposUrl = teamProjectUrl + "/_apis/git/repositories?api-version=1";
        org.json.JSONObject obj = callGet(client, listOfReposUrl);
        JSONArray repos = obj.getJSONArray("value");
        for (int i = 0; i < repos.length(); i++) {
            org.json.JSONObject repo = repos.getJSONObject(i);
            listener.getLogger().println("Found repo: - " + repo.get("id") + " - " + repo.get("name"));
        }
        return repos;
    }

    private JSONArray GetBranchesForRepo(TaskListener listener, OkHttpClient client, String teamProjectUrl, org.json.JSONObject repo) throws IOException {
        String listOfBranchesUrl = teamProjectUrl + "/_apis/git/repositories/" + repo.get("id") + "/refs?filter=heads&api-version=1.0";
        org.json.JSONObject obj = callGet(client, listOfBranchesUrl);
        JSONArray branches = obj.getJSONArray("value");
        listener.getLogger().println("Repo: " + repo.get("name"));
        for (int j = 0; j < branches.length(); j++) {
            org.json.JSONObject branch = branches.getJSONObject(j);
            listener.getLogger().println("Found branch: " + branch.get("name"));
        }

        return branches;
    }

    private boolean checkBranchesForFile(TaskListener listener, OkHttpClient client, String teamProjectUrl, String repoId, org.json.JSONObject branch, String file) throws IOException {
        String branchName = branch.get("name").toString().substring(11);
        listener.getLogger().println("\t--Looking through branch: " + branchName + " for a jenkinsfile--");
        String jenkinsFileMetadataUrl = teamProjectUrl + "/_apis/git/repositories/" + repoId + "/items?api-version=1.0&version=" + branchName + "&scopepath=/" + file;
        org.json.JSONObject obj = callGet(client, jenkinsFileMetadataUrl);
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

    private OkHttpClient createClient(final StandardUsernamePasswordCredentials tfsCredentials) {
        OkHttpClient client = new OkHttpClient();
        client.setAuthenticator(new Authenticator() {
            @Override
            public Request authenticate(Proxy proxy, Response response) throws IOException {
                String cred = com.squareup.okhttp.Credentials.basic(tfsCredentials.getUsername(), tfsCredentials.getPassword().getPlainText());
                return response.request().newBuilder().header("Authorization", cred).build();
            }

            @Override
            public Request authenticateProxy(Proxy proxy, Response response) throws IOException {
                return null;
            }
        });
        return client;
    }

    private org.json.JSONObject callGet(OkHttpClient client, String url) throws IOException {
        Request okRequest = new Request.Builder()
                .url(url)
                .addHeader("content-type", "application/json")
                .addHeader("accept", "application/json")
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

    private InputStream replaceTokensInXML(File xmlFile, String repoName, String credentialsId, String url, String file, String team) throws IOException {
        BufferedReader br = null;
        String newString;
        StringBuilder strTotale = new StringBuilder();
        try {

            FileReader reader = new FileReader(xmlFile);
            String repoToken = "#repo#";
            String guidToken = "#guid#";
            String credentialsToken = "#credentialsId#";
            String urlToken = "#url#";
            String fileToken = "#fileType#";
            String teamToken = "#team#";

            br = new BufferedReader(reader);
            while ((newString = br.readLine()) != null) {
                newString = newString.replaceAll(repoToken, repoName);
                newString = newString.replaceAll(guidToken, java.util.UUID.randomUUID().toString());
                newString = newString.replaceAll(credentialsToken, credentialsId);
                newString = newString.replaceAll(urlToken, url);
                newString = newString.replaceAll(fileToken, file);
                newString = newString.replaceAll(teamToken, team);
                strTotale.append(newString);
            }

        } catch (IOException e) {
            e.printStackTrace();
        } // calls it
        finally {
            try {
                br.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        InputStream xml = new ByteArrayInputStream(strTotale.toString().getBytes(StandardCharsets.UTF_8));
        return xml;
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
