Akka
====
*Akka is a powerful platform that simplifies building and operating highly responsive, resilient, and scalable services.*


The platform consists of
* the [**Akka SDK**](https://doc.akka.io/java/index.html) for straightforward, rapid development with AI assist and automatic clustering. Services built with the Akka SDK are automatically clustered and can be deployed on any infrastructure.
* and [**Akka Automated Operations**](https://doc.akka.io/operations/akka-platform.html), a managed solution that handles everything for Akka SDK services from auto-elasticity to multi-region high availability running safely within your VPC.

The **Akka SDK** and **Akka Automated Operations** are built upon the foundational [**Akka libraries**](https://doc.akka.io/libraries/akka-dependencies/current/), providing the building blocks for distributed systems.


Akka HTTP library
=================
The Akka HTTP modules implement a full server- and client-side HTTP stack on top
of akka-actor and akka-stream. It's not a web-framework but rather a more
general toolkit for providing and consuming HTTP-based services. While
interaction with a browser is of course also in scope it is not the primary
focus of Akka HTTP.

Akka HTTP follows a rather open design and many times offers several different
API levels for "doing the same thing". You get to pick the API level of
abstraction that is most suitable for your application. This means that, if you
have trouble achieving something using a high-level API, there's a good chance
that you can get it done with a low-level API, which offers more flexibility but
might require you to write more application code.

Reference Documentation
-----------------------

The reference documentation for all Akka libraries is available via [doc.akka.io/libraries/](https://doc.akka.io/libraries/), details for the Akka HTTP library
for [Scala](https://doc.akka.io/libraries/akka-http/current/?language=scala) and [Java](https://doc.akka.io/libraries/akka-http/current/?language=java).

The current versions of all Akka libraries are listed on the [Akka Dependencies](https://doc.akka.io/libraries/akka-dependencies/current/) page. Releases of the Akka HTTP library in this repository are listed on the [GitHub releases](https://github.com/akka/akka-http/releases) page.

Contributing
------------
Contributions are *very* welcome!

If you see an issue that you'd like to see fixed, the best way to make it happen is to help out by submitting a pull request.
For ideas of where to contribute, [tickets marked as "help wanted"](https://github.com/akka/akka-http/labels/help%20wanted) are a good starting point.

Refer to the [CONTRIBUTING.md](CONTRIBUTING.md) file for more details about the workflow,
and general hints on how to prepare your pull request. You can also ask for clarifications or guidance in GitHub issues directly.

Maintenance
-----------

This project is maintained by the core Akka Team as well as the extended Akka HTTP Team, consisting of excellent and experienced developers who have shown their dedication and knowledge about HTTP and the codebase. This team may grow dynamically, and it is possible to propose new members to it.

Joining the extended team in such form gives you, in addition to street-cred, of course committer rights to this repository as well as higher impact onto the roadmap of the project. Come and join us!

License
-------

Akka is licensed under the Business Source License 1.1, please see the [Akka License FAQ](https://akka.io/bsl-license-faq).

Tests and documentation are under a separate license, see the LICENSE file in each documentation and test root directory for details.
