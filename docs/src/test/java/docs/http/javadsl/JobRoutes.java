/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

//#route
import java.time.Duration;
import java.util.concurrent.CompletionStage;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.AskPattern;

import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;

import static akka.http.javadsl.server.Directives.*;
import static akka.http.javadsl.unmarshalling.StringUnmarshallers.LONG;

/**
 * Routes for use with the HttpServerWithActorsSample
 */
public class JobRoutes {
  private static Route addOrDelete(ActorRef<JobRepository.Command> buildJobRepository, ActorSystem<?> system) {
    return concat(
            post(() ->
                    entity(Jackson.unmarshaller(JobRepository.Job.class), job -> {
                      CompletionStage<JobRepository.Response> operationPerformed = AskPattern.ask(
                              buildJobRepository,
                              replyTo -> new JobRepository.AddJob(job, replyTo),
                              Duration.ofSeconds(3),
                              system.scheduler());
                      return onSuccess(operationPerformed, response -> {
                        if (response instanceof JobRepository.OK) {
                          return complete("Job added");
                        } else if (response instanceof JobRepository.KO) {
                          return complete(StatusCodes.INTERNAL_SERVER_ERROR, ((JobRepository.KO) response).reason);
                        } else {
                          return complete(StatusCodes.INTERNAL_SERVER_ERROR);
                        }
                      });
                    })),
            delete(() -> {
              CompletionStage<JobRepository.Response> operationPerformed = AskPattern.ask(
                      buildJobRepository,
                      JobRepository.ClearJobs::new,
                      Duration.ofSeconds(3),
                      system.scheduler());
              return onSuccess(operationPerformed, response -> {
                if (response instanceof JobRepository.OK) {
                  return complete("Jobs cleared");
                } else if (response instanceof JobRepository.KO) {
                  return complete(StatusCodes.INTERNAL_SERVER_ERROR, ((JobRepository.KO) response).reason);
                } else {
                  return complete(StatusCodes.INTERNAL_SERVER_ERROR);
                }
              });
            })
    );
  }

  public static Route jobRoutes(ActorRef<JobRepository.Command> buildJobRepository, ActorSystem<?> system) {
    return pathPrefix("jobs", () ->
            concat(
                    pathEnd(() ->
                            addOrDelete(buildJobRepository, system)
                    ),
                    get(() ->
                            path(LONG, jobId -> {
                              CompletionStage<JobRepository.Job> maybeJob = AskPattern.ask(
                                      buildJobRepository,
                                      replyTo -> new JobRepository.GetJobById(jobId, replyTo),
                                      Duration.ofSeconds(3),
                                      system.scheduler());
                              return onSuccess(maybeJob, job ->
                                      complete(StatusCodes.OK, job, Jackson.<JobRepository.Job>marshaller()));
                            })
                    )
            )
    );
  }
}
//#route