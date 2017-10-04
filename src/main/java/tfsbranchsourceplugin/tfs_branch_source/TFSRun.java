package tfsbranchsourceplugin.tfs_branch_source;

import com.cloudbees.plugins.credentials.UserCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.squareup.okhttp.Authenticator;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.Result;

import java.io.*;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import jenkins.model.Jenkins;
import org.json.JSONArray;
import org.json.JSONObject;

public class TFSRun extends Build<TeamFoundationServerSCM, TFSRun> {
    public TFSRun(TeamFoundationServerSCM job) throws IOException {
        super(job);
    }

    public TFSRun(TeamFoundationServerSCM job, Calendar timestamp) {
        super(job, timestamp);
    }

    public TFSRun(TeamFoundationServerSCM job, File buildDir) throws IOException {
        super(job, buildDir);
    }


    public TeamFoundationServerSCM getTeamFoundationServerSCM() {
        return project;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        this.execute(new TFSImpl());
    }

    protected class TFSImpl extends AbstractBuildExecution {

        public TFSImpl() {
        }

        @Override
        protected Result doRun(BuildListener buildListener) throws Exception {

            //replace these from config
            String teamProjectUrl = "https://tfs.generalmills.com/tfs/GitCollection/cloverleaf/";
            String file = "Jenkinsfile";
            String credentialsUsername = "GENMILLS\\M080905";

            final StandardUsernamePasswordCredentials tfsCredentials = getStandardUsernamePasswordCredentials(credentialsUsername);
            ArrayList<String> reposWithFile = new ArrayList<>();

            OkHttpClient client = createClient(tfsCredentials);

            buildListener.getLogger().println("--Looking through Team Project for repos--");
            JSONArray repos = GetReposForTeamProject(buildListener, client, teamProjectUrl);

            for(int i = 0; i < repos.length(); i++)
            {
                buildListener.getLogger().println("\n\n--Looking through repo for branches--");
                JSONArray branches = GetBranchesForRepo(buildListener, client, teamProjectUrl, repos.getJSONObject(i));

                for(int j = 0; j < branches.length(); j++)
                {
                    if(checkBranchesForFile(buildListener, client, teamProjectUrl, repos.getJSONObject(i).get("id").toString(), branches.getJSONObject(j), file))
                    {
                        //Once we have found the file we are looking for, we don't need to check the rest of the branches
                        reposWithFile.add(repos.getJSONObject(i).get("name").toString());
                        break;
                    }
                }
            }

            buildListener.getLogger().println("\n\n--Repos with the file--");
            for(String name : reposWithFile)
            {
                File xmlFile = new File("C:\\Projects\\GMITFSPlugin\\tfs_vsts_branch_source\\xml\\config.xml");
                buildListener.getLogger().println(name);
                InputStream foobar = replaceTokensInXML(xmlFile, name);
                try
                {
                    Jenkins.getInstance().createProjectFromXML(name + "-MBP", foobar);
                }
                //Happens when a job already exists with the given name
                catch(IllegalArgumentException e)
                {
                    buildListener.getLogger().println(e.getMessage());
                }

            }

            return null;
        }

        @Override
        protected void post2(BuildListener buildListener) throws Exception {

        }
    }

    private JSONArray GetReposForTeamProject(BuildListener buildListener, OkHttpClient client, String teamProjectUrl) throws IOException {
        String listOfReposUrl = teamProjectUrl + "_apis/git/repositories?api-version=1";
        JSONObject obj = callGet(client, listOfReposUrl);
        JSONArray repos = obj.getJSONArray("value");
        for(int i = 0; i < repos.length(); i++)
        {
            JSONObject repo = repos.getJSONObject(i);
            buildListener.getLogger().println("Found repo: - " + repo.get("id") + " - " + repo.get("name"));
        }
        return repos;
    }

    private JSONArray GetBranchesForRepo(BuildListener buildListener, OkHttpClient client, String teamProjectUrl, JSONObject repo) throws IOException {
        String listOfBranchesUrl = teamProjectUrl + "_apis/git/repositories/"+ repo.get("id") +"/refs?filter=heads&api-version=1.0";
        JSONObject obj = callGet(client, listOfBranchesUrl);
        JSONArray branches = obj.getJSONArray("value");
        buildListener.getLogger().println("Repo: " + repo.get("name"));
        for(int j = 0; j < branches.length(); j++)
        {
            JSONObject branch = branches.getJSONObject(j);
            buildListener.getLogger().println("Found branch: " + branch.get("name"));
        }

        return branches;
    }

    private boolean checkBranchesForFile(BuildListener buildListener, OkHttpClient client, String teamProjectUrl, String repoId, JSONObject branch, String file) throws IOException {
        String branchName = branch.get("name").toString().substring(11);
        buildListener.getLogger().println("\t--Looking through branch: " + branchName + " for a jenkinsfile--");
        String jenkinsFileMetadataUrl = teamProjectUrl + "_apis/git/repositories/" + repoId + "/items?api-version=1.0&version=" + branchName + "&scopepath=/" + file;
        JSONObject obj = callGet(client, jenkinsFileMetadataUrl);
        if(obj.has("value")) {
            JSONArray fileList = obj.getJSONArray("value");
            for (int k = 0; k < fileList.length(); k++) {
                buildListener.getLogger().println("\tFOUND JENKINS FILE!");
                return true;
            }
        }
        else{
            buildListener.getLogger().println("\tNone found");
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

    private JSONObject callGet(OkHttpClient client, String url) throws IOException {
        Request okRequest = new Request.Builder()
                .url(url)
                .addHeader("content-type", "application/json")
                .addHeader("accept", "application/json")
                .build();
        Response response = client.newCall(okRequest).execute();

        String json = response.body().string();
        response.body().close();
        return new JSONObject(json);
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

}
