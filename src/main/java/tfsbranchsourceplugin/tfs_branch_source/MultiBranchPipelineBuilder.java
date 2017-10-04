package tfsbranchsourceplugin.tfs_branch_source;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.UserCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.squareup.okhttp.Authenticator;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.json.JSONArray;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.*;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MultiBranchPipelineBuilder extends Builder implements SimpleBuildStep {

    private final String teamProjectUrl;
    private final String teamProject;
    private final String username;
    private final String projectRecognizer;


    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public MultiBranchPipelineBuilder(String teamProjectUrl, String teamProject, String username, String projectRecognizer) {
        this.teamProjectUrl = teamProjectUrl;
        this.teamProject = teamProject;
        this.username = username;
        this.projectRecognizer = projectRecognizer;

    }

    /**
     * We'll use this from the {@code config.jelly}.
     */
    public String getTeamProjectUrl() {
        return teamProjectUrl;
    }
    public String getTeamProject() {
        return teamProject;
    }
    public String getUsername() {
        return username;
    }
    public String getProjectRecognizer() {
        return projectRecognizer;
    }


    @Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {

        String url = teamProjectUrl;
        String team = teamProject;
        String file = projectRecognizer;
        String credentialsUsername = username;

        final StandardUsernamePasswordCredentials tfsCredentials;
        try
        {
            tfsCredentials = getStandardUsernamePasswordCredentials(credentialsUsername);
        } catch (Exception e)
        {
            listener.getLogger().println(e.getMessage());
            return;
        }
        ArrayList<String> reposWithFile = new ArrayList<>();

        OkHttpClient client = createClient(tfsCredentials);

        listener.getLogger().println("--Looking through Team Project for repos--");
        JSONArray repos = GetReposForTeamProject(listener, client, url);

        for(int i = 0; i < repos.length(); i++)
        {
            listener.getLogger().println("\n\n--Looking through repo for branches--");
            JSONArray branches = GetBranchesForRepo(listener, client, url, repos.getJSONObject(i));

            for(int j = 0; j < branches.length(); j++)
            {
                if(checkBranchesForFile(listener, client, url, repos.getJSONObject(i).get("id").toString(), branches.getJSONObject(j), file))
                {
                    //Once we have found the file we are looking for, we don't need to check the rest of the branches
                    reposWithFile.add(repos.getJSONObject(i).get("name").toString());
                    break;
                }
            }
        }

        listener.getLogger().println("\n\n--Repos with the file--");
        Folder folder = new Folder(Jenkins.getInstance(), teamProject);
        for(String name : reposWithFile)
        {
            File xmlFile = new File("C:\\Projects\\GMITFSPlugin\\tfs_vsts_branch_source\\xml\\config.xml");
            listener.getLogger().println(name);
            InputStream foobar = replaceTokensInXML(xmlFile, name);
            try
            {
                folder.createProjectFromXML(name, foobar);
            }
            //Happens when a job already exists with the given name
            catch(IllegalArgumentException e)
            {
                listener.getLogger().println(e.getMessage());
            }

        }

    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public MultiBranchPipelineBuilder.DescriptorImpl getDescriptor() {
        return (MultiBranchPipelineBuilder.DescriptorImpl)super.getDescriptor();
    }

    private JSONArray GetReposForTeamProject(TaskListener listener, OkHttpClient client, String teamProjectUrl) throws IOException {
        String listOfReposUrl = teamProjectUrl + "_apis/git/repositories?api-version=1";
        org.json.JSONObject obj = callGet(client, listOfReposUrl);
        JSONArray repos = obj.getJSONArray("value");
        for(int i = 0; i < repos.length(); i++)
        {
            org.json.JSONObject repo = repos.getJSONObject(i);
            listener.getLogger().println("Found repo: - " + repo.get("id") + " - " + repo.get("name"));
        }
        return repos;
    }

    private JSONArray GetBranchesForRepo(TaskListener listener, OkHttpClient client, String teamProjectUrl, org.json.JSONObject repo) throws IOException {
        String listOfBranchesUrl = teamProjectUrl + "_apis/git/repositories/"+ repo.get("id") +"/refs?filter=heads&api-version=1.0";
        org.json.JSONObject obj = callGet(client, listOfBranchesUrl);
        JSONArray branches = obj.getJSONArray("value");
        listener.getLogger().println("Repo: " + repo.get("name"));
        for(int j = 0; j < branches.length(); j++)
        {
            org.json.JSONObject branch = branches.getJSONObject(j);
            listener.getLogger().println("Found branch: " + branch.get("name"));
        }

        return branches;
    }

    private boolean checkBranchesForFile(TaskListener listener, OkHttpClient client, String teamProjectUrl, String repoId, org.json.JSONObject branch, String file) throws IOException {
        String branchName = branch.get("name").toString().substring(11);
        listener.getLogger().println("\t--Looking through branch: " + branchName + " for a jenkinsfile--");
        String jenkinsFileMetadataUrl = teamProjectUrl + "_apis/git/repositories/" + repoId + "/items?api-version=1.0&version=" + branchName + "&scopepath=/" + file;
        org.json.JSONObject obj = callGet(client, jenkinsFileMetadataUrl);
        if(obj.has("value")) {
            JSONArray fileList = obj.getJSONArray("value");
            for (int k = 0; k < fileList.length(); k++) {
                listener.getLogger().println("\tFOUND JENKINS FILE!");
                return true;
            }
        }
        else{
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

    private StandardUsernamePasswordCredentials getStandardUsernamePasswordCredentials(String credentialsUsername) throws Exception {
        ClassLoader loader = Jenkins.getInstance().pluginManager.getPlugin("credentials").classLoader;

        UserCredentialsProvider provider = new UserCredentialsProvider();
        Class credentialType = loader.loadClass("com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials");
        List<StandardUsernamePasswordCredentials> credentials = provider.lookupCredentials(credentialType);

        StandardUsernamePasswordCredentials tfsCredentials = null;
        for(StandardUsernamePasswordCredentials c : credentials)
        {
            if(c.getUsername().equals(credentialsUsername))
            {
                tfsCredentials = c;
            }
        }
        if(tfsCredentials == null)
        {
            throw new Exception("No TFS username found");
        }
        return tfsCredentials;
    }

    private InputStream replaceTokensInXML(File xmlFile, String repoName) throws IOException {
        BufferedReader br = null;
        String newString;
        StringBuilder strTotale = new StringBuilder();
        try {

            FileReader reader = new FileReader(xmlFile);
            String search = "#repo#";
            String guid = "#guid#";

            br = new BufferedReader(reader);
            while ((newString = br.readLine()) != null){
                newString = newString.replaceAll(search, repoName);
                newString = newString.replaceAll(guid, java.util.UUID.randomUUID().toString());
                strTotale.append(newString);
            }

        } catch ( IOException  e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } // calls it
        finally
        {
            try {
                br.close();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        InputStream xml = new ByteArrayInputStream(strTotale.toString().getBytes(StandardCharsets.UTF_8));
        return xml;
    }

    /**
     * Descriptor for {@link HelloWorldBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See {@code src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly}
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use {@code transient}.
         */
        private boolean useFrench;

        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user.
         */
        public FormValidation doCheckName(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a name");
            if (value.length() < 4)
                return FormValidation.warning("Isn't the name too short?");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Generate MultiBranch pipeline from TFS GIT";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            //useFrench = formData.getBoolean("useFrench");
            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)
            save();
            return super.configure(req,formData);
        }

        /**
         * This method returns true if the global configuration says we should speak French.
         *
         * The method name is bit awkward because global.jelly calls this method to determine
         * the initial state of the checkbox by the naming convention.
         *
        public boolean getUseFrench() {
            return useFrench;
        }*/
    }
}
