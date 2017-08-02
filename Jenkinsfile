pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        sh '''set -x
find .git
printenv
echo "#######################"
echo $BRANCH_NAME
echo "#######################"
git branch -r
git fetch origin
git branch -r
git branch -vv

echo "#######################"

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