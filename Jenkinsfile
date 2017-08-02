pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        sh '''set -x
printenv
echo "#######################"
echo $BRANCH_NAME
echo "#######################"
git branch -r
git branch -vv
cat .git/config

echo "#######################"

if [ $BRANCH_NAME == "master" ]; then
  git diff --name-only HEAD HEAD~1 > changed_files.txt
else
  git diff --name-only origin/master > changed_files.txt
fi

cat changed_files.txt
'''
      }
    }
  }
}