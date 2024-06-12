@Library('c8y-common-steps') _

VERSIONS = [:]

pipeline {
  agent {
    kubernetes {
      defaultContainer 'java'
      yamlFile '.github/jobs/release-pod.yaml'
    }
  }
  options {
    timeout(time: 1, unit: 'HOURS')
    disableConcurrentBuilds()
  }
  environment {
    MVN_SETTINGS = credentials('maven-settings')
    GIT_CREDENTIALS = credentials('tech-c8y-github.softwareag.com')
  }
  stages {
    stage('Prepare') {
      steps {
        script {
          VERSIONS.current = sh(returnStdout: true,
              script: '.github/scripts/mvn.sh --non-recursive --quiet exec:exec --define \'exec.executable=echo\' --define \'exec.args=${revision}\''
          ).trim()
          VERSIONS.next = nextReleaseVersionOf(VERSIONS.current)
        }
        echo "Releasing version ${VERSIONS.current} and updating to next development version ${VERSIONS.next}"
      }
    }
    stage('Build release') {
      steps {
        echo "Updating to next release version: ${VERSIONS.current}"
        sh ".github/scripts/mvn.sh --non-recursive versions:set-property --define 'property=changelist' --define 'newVersion='"
        sh "git add . && git commit -m \"chore(release): updated release version to ${VERSIONS.current}\""
        sh "git tag -am 'Release ${VERSIONS.current}' dependencies-${VERSIONS.current}"

        echo "Publishing artifacts of next release version: ${VERSIONS.current}"
        sh ".github/scripts/mvn.sh clean deploy"
      }
    }
    stage('Build next snapshot') {
      steps {
        echo "Updating to next snapshot version: ${VERSIONS.next}"
        sh ".github/scripts/mvn.sh --non-recursive versions:set-property --define 'property=revision' --define 'newVersion=${VERSIONS.next}'"
        sh ".github/scripts/mvn.sh --non-recursive versions:set-property --define 'property=changelist' --define 'newVersion=-SNAPSHOT'"
        sh "git add . && git commit -m \"chore(release): updated development version to ${VERSIONS.next}\""

        echo "Publishing artifacts of next snapshot version: ${VERSIONS.next}"
        sh ".github/scripts/mvn.sh clean deploy"
      }
    }
    stage('Finish') {
      steps {
        echo "Publishing changes to git"
        sh "git push --follow-tags https://${GIT_CREDENTIALS}@github.softwareag.com/IOTA/cumulocity-dependencies.git ${BRANCH_NAME}"
      }
    }
  }
}

def nextReleaseVersionOf(revision) {
  String[] versions = revision.split('\\.')
  int major = Integer.parseInt(versions[0])
  int minor = Integer.parseInt(versions[1])
  int maintenance = Integer.parseInt(versions[2])
  if (maintenance > 0) {
    return "${major}.${minor}.${maintenance + 1}".toString()
  } else {
    return "${major}.${minor + 1}.${maintenance}".toString()
  }
}
