pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        parallel(
          "Build": {
            echo 'Build stage started'
            
          },
          "Build2": {
            echo 'Build2 stage started'
            
          },
          "Build3": {
            echo 'Build3 stage started'
            
          }
        )
      }
    }
    stage('Decide what to build') {
      steps {
        sh '''echo "1\n3\n" >what-to-build.txt
'''
      }
    }
  }
}