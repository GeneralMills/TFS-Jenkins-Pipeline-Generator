package tfsbranchsourceplugin.tfs_branch_source;

import hudson.Extension;
import hudson.model.*;
import org.kohsuke.stapler.DataBoundConstructor;

public class TeamFoundationServerSCM extends Project<TeamFoundationServerSCM, TFSRun> implements TopLevelItem{

    @DataBoundConstructor
    public TeamFoundationServerSCM(ItemGroup parent, String name) {
        super(parent, name);
    }

    @Override
    public TeamFoundationServerSCMDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected Class<TFSRun> getBuildClass() {
        return TFSRun.class;
    }

    @Extension
    public static final TeamFoundationServerSCMDescriptor DESCRIPTOR = new TeamFoundationServerSCMDescriptor();

    public static class TeamFoundationServerSCMDescriptor extends AbstractProject.AbstractProjectDescriptor {

        @Override
        public TopLevelItem newInstance(ItemGroup itemGroup, String s) {
            return new TeamFoundationServerSCM(itemGroup, s);
        }

        //TODO Move to string constants somewhere
        @Override
        public String getDisplayName() {
            return "TFS VSTS Organization";
        }

        @Override
        public String getDescription() {
            return "Scans a TFS or VSTS organization for all repositories that have a JenkinsFile and makes a multibranch pipeline";
        }

        //TODO get icon
    }
}
