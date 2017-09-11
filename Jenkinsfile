#!groovy

node('build-slave') {

   currentBuild.result = "SUCCESS"

   try {

      stage('Checkout'){

         checkout scm
       }

      stage('Build'){
        cleanWs()
        env.NODE_ENV = "build"

        print "Environment will be : ${env.NODE_ENV}"
        sh ('mkdir learner-actors')
        sh('mv actors/ sunbird-common/ learner-actors')
         dir ('learner-actors/actors') {
        sh 'mvn clean install -DskipTests=true'
         }

         sh('chmod 777 ./build.sh')
         sh('./build.sh')
      }

      stage('Publish'){

        echo 'Push to Repo'
        sh 'ls -al ~/'
        sh('chmod 777 ./dockerPushToRepo.sh')
        sh 'ARTIFACT_LABEL=bronze ./dockerPushToRepo.sh'
        sh './metadata.sh > metadata.json'      
        sh 'cat metadata.json'
        archive includes: "metadata.json"
        cleanWs()
      }
      }
    catch (err) {
        currentBuild.result = "FAILURE"
        throw err
    }

}
