Running the application
-----------------------

When you ran the example for the first time, you were able to create and retrieve multiple users. Now that you understand how the example is implemented, let's confirm that the rest of the functionality works. We want to verify that:

* If we try to retrieve users when none exist, we get an empty list.
* If we try to retrieve a specific user that doesn't exist, we get a HTTP 404 `The requested resource could not be found` response.
* We can delete users.

To test this functionality, follow these steps. If you need reminders on starting the app or sending requests, refer to the @ref:[instructions](index.md#exercising-the-example) in the beginning.

`1.` If the Akka HTTP server is still running, stop and restart it.
`2.` With no users registered, use your tool of choice to:
`3.` Retrieve a list of users. Hint: use the `GET` method and append `/users` to the URL.

You should get back an empty list: `{"users":[]}`

`4.` Try to retrieve a single user named `MrX`. Hint: use the `GET` method and append `users/MrX` to the URL.

You should get back the message: `User MrX is not registered.`

`5.` Try adding one or more users. Hint: use the `POST` method, append `/users` to the URL, and format the data in JSON, similar to: `{"name":"MrX","age":31,"countryOfResidence":"Canada"}`

You should get back the message: `User MrX created.`

`6.` Try deleting a user you just added. Hint: use the `DELETE`, and append `/users/<NAME>` to the URL.

You should get back the message: `User MrX deleted.`

Now that you've confirmed all of the example functionality, see how simple it is to integrate the project into an IDE.
