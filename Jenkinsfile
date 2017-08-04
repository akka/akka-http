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
  }
}