pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        sh '''set -x
printenv
echo $BRANCH_NAME=jenkins-test1

if [ $BRANCH_NAME == "master" ]; then
  git diff --name-only HEAD HEAD~1 > changed_files.txt
else
  git diff --name-only master > changed_files.txt
fi

cat changed_files.txt
'''
      }
    }
  }
}