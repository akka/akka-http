# Sample Akka HTTP server

This is a sample Akka HTTP endpoint keeping an in-memory database of users that can be created and listed.

Sources in the sample:

* `QuickstartApp.scala` -- contains the main method which bootstraps the application
* `UserRoutes.scala` -- Akka HTTP `routes` defining exposed endpoints
* `UserRegistry.scala` -- the actor which handles the registration requests
* `JsonFormats.scala` -- converts the JSON data from requests into Scala types and from Scala types into JSON responses

## Interacting with the sample

After starting the sample with `sbt run` the following requests can be made:

List all users:

    curl http://localhost:8080/users

Create a user:

    curl -XPOST http://localhost:8080/users -d '{"name": "Liselott", "age": 32, "countryOfResidence": "Norway"}' -H "Content-Type:application/json"

Get the details of one user:

    curl http://localhost:8080/users/Liselott

Delete a user:

    curl -XDELETE http://localhost:8080/users/Liselott