OSGi integration tests for Akka HTTP
====================================

This project provides an automated check to verify whether the artifacts 
produced by Akka HTTP are valid OSGi bundles and can thus be used in an OSGi
environment.

The test is based on [PaxExam](https://ops4j1.jira.com/wiki/spaces/PAXEXAM4/overview).
This tool is used to deploy the Akka HTTP bundles and their dependencies in an
OSGi container and execute some simple actions on them.

In order to execute the OSGi integration test, in the SBT console switch to the
_akka-http-osgi-tests_ project and execute the _osgiTest_ task.
