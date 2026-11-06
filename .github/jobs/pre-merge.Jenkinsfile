// Deploys the build of a pull request to the pre-merge repository, as <revision>.<PR>.<build>.deps.
// cumulocity-core resolves it from there: a core branch whose parent is set to that version builds against
// this pull request in both its Jenkins and its GitHub Actions checks.
// The GitHub PreMerge workflow stays the build check of the pull request; this job only publishes.

pipeline {
  agent {
    kubernetes {
      defaultContainer 'java'
      yamlFile '.github/jobs/release-pod.yaml'
    }
  }
  options {
    timeout(time: 30, unit: 'MINUTES')
    disableConcurrentBuilds(abortPrevious: true)
  }
  environment {
    MVN_SETTINGS = credentials('maven-settings')
    CHANGE_VERSION = ".${env.CHANGE_ID}.${env.BUILD_NUMBER}.deps"
  }
  stages {
    stage('Deploy') {
      when { changeRequest() }
      steps {
        script {
          def version = sh(returnStdout: true,
              script: '.github/scripts/mvn.sh --non-recursive --quiet exec:exec --define \'exec.executable=echo\' --define \'exec.args=${project.version}\''
          ).trim()
          currentBuild.description = version
          echo "Deploying ${version} to pre-merge"
        }
        // Sequential uploads: parallel ones hit HTTP 429 from AWS CodeArtifact
        sh '.github/scripts/mvn.sh --activate-profiles pre-merge -DskipTests -Daether.connector.basic.parallelPut=false deploy'
      }
    }
  }
}
