@Library('jenkins-build-plugin') _
pipeline {
    agent any
    stages {
        stage('Package plugin') {
           steps {
                echo "Hello"
                buildPlugin(jdkVersions: [8], platforms: ['windows'])
           }
        }
        stage('Upload to Artifactory') {
            steps {
                script {
                    def server = Artifactory.server('Test-Artifactory')

                    def upload = """{
                      "files": [
                        {
                          "pattern": "*/tfs_branch_source.hpi",
                          "target": "maven-snapshot-local/"
                        }
                      ]
                    }"""

                    // Upload files to Artifactory:
                    def uploadInfo = server.upload(spec: upload)

                    // Publish the info to Artifactory
                    server.publishBuildInfo uploadInfo
                }
            }
        }
    }
}
