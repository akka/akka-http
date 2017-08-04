pipeline {
  agent any
  stages {
    stage('Decide what to build') {
      steps {
        sh '''echo "1
3
" >what-to-build.txt
'''
      }
    }
    stage('Build1') {
      steps {
        parallel(
          "Build1": {
            echo 'Build1 start'
            sh '''cat what-to-build.txt
'''
            
          },
          "Build2": {
            echo 'Build2 start'
            sh '''cat what-to-build.txt
'''
            
          },
          "Build3": {
            echo 'Build3 start'
            sh '''cat what-to-build.txt
'''
            
          }
        )
      }
    }
  }
}