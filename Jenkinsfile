#!groovy

timestamps {
    def pennsieveNexusCreds = usernamePassword(
        credentialsId: 'pennsieve-nexus-ci-login',
        usernameVariable: 'PENNSIEVE_NEXUS_USER',
        passwordVariable: 'PENNSIEVE_NEXUS_PW'
    )

    node('executor-new') {
        checkout scm

        def commitHash  = sh(returnStdout: true, script: 'git rev-parse HEAD | cut -c-7').trim()
        def imageTag = "${env.BUILD_NUMBER}-${commitHash}"
        def remoteCache = "remote-cache-${imageTag}"
        def sbt = "sbt -Dsbt.log.noformat=true -Dversion=$imageTag -Dremote-cache=$remoteCache"

        try {

            stage('Build') {
                withCredentials([pennsieveNexusCreds]) {
                    sh "${sbt} clean compile pushRemoteCache"
                }
                stash name: "${remoteCache}", includes: "${remoteCache}/**/*"
            }

            stage('Test') {
                unstash name: "${remoteCache}"
                withCredentials([pennsieveNexusCreds]) {
                    try {
                        sh "${sbt} clean pullRemoteCache test"
                    } finally {
                        junit '**/target/test-reports/*.xml'
                    }
                }
            }

            if (env.BRANCH_NAME == 'main') {
                def services = [ 'discover-publish' ]
                def containers = services + []

                stage('Publish Containers') {
                    def publishContainerSteps = containers.collectEntries {
                        ["${it}" : generatePublishContainerStep(it, sbt, imageTag, pennsieveNexusCreds, remoteCache)]
                    }
                    publishContainerSteps.failFast = true
                    parallel publishContainerSteps
                }

                stage('Deploy') {
                    def deploySteps = services.collectEntries {
                        ["${it}" : generateDeployStep(it, imageTag)]
                    }
                    deploySteps.failFast = true
                    parallel deploySteps
                }

            }
        } catch (e) {
            currentBuild.result = 'FAILED'
            throw e
        } finally {
            notifyBuild(currentBuild.result)
        }
    }
}

// Generate parallel deploy steps
def generateDeployStep(String service, String imageTag) {
    return {
        build job: "service-deploy/pennsieve-non-prod/us-east-1/dev-vpc-use1/dev/${service}",
        parameters: [
            string(name: 'IMAGE_TAG', value: imageTag),
            string(name: "TERRAFORM_ACTION", value: "apply")
        ]
    }
}

// Generate parallel container publish steps
def generatePublishContainerStep(String service, String sbt, String imageTag, creds, String remoteCache) {
    return {
        node('executor-new') {
            checkout scm
            unstash name: "${remoteCache}"

            // Handle exceptions to standard service deploys
            // discover-publish and uploads-consumer utilize multiple containers
            def images, tag, buildPath
            switch(service) {
                case 'discover-publish':
                    (images, tag) = [[service, 'discover-pgdump-postgres'], imageTag]
                    buildPath = 'discover-publish/'
                    break
                default:
                    (images, tag) = [[service], imageTag]
                    break
            }

            withCredentials([creds]) {
                sh "${sbt} clean pullRemoteCache ${service}/docker"
            }

            for (image in images) {
                if (['clamd', 'discover-pgdump-postgres'].contains(image)) {
                    sh "docker build --no-cache --tag pennsieve/${image}:latest ${buildPath}"
                }
                sh "docker tag pennsieve/${image}:latest pennsieve/${image}:${tag}"
                sh "docker push pennsieve/${image}:latest"
                sh "docker push pennsieve/${image}:${tag}"
            }
        }
    }
}

// Slack build status notifications
def notifyBuild(String buildStatus) {
  // Build status of null means successful
  buildStatus = buildStatus ?: 'SUCCESS'

  def authorName = sh(returnStdout: true, script: 'git --no-pager show --format="%an" --no-patch').trim()
  def color
  def message = "${buildStatus}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL}) by ${authorName}"

  if (buildStatus == 'SUCCESS') {
    color = '#00FF00' // Green
  } else {
    color = '#FF0000' // Red
  }

  slackSend(color: color, message: message)
}
