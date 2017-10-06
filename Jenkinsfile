@Library('jenkins-build-plugin') _
pipeline {
    agent any
    stages {
        stage('Package plugin') {
           steps {
                buildPlugin(jdkVersions: [8], platforms: ['windows'])
           }
        }
        stage('Upload to Artifactory') {
            steps {
                script {
                    def server = Artifactory.server 'Test-Artifactory'

                    // Read the upload specs:
                    def upload = readfile 'props-upload.json'

                    // Upload files to Artifactory:
                    def uploadInfo = server.upload spec: uploadSpec

                    // Publish the info to Artifactory
                    server.publishBuildInfo uploadInfo
                }
            }
        }
    }
}