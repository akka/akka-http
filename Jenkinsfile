pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        sh '''printenv
#set -x
#printenv
#echo $GIT_BRANCH

#if [ $GIT_BRANCH == "origin/master" ]; then
#  git diff --name-only HEAD HEAD~1 > changed_files.txt
#else
#  git diff --name-only master > changed_files.txt
#fi

#cat changed_files.txt
'''
      }
    }
  }
}