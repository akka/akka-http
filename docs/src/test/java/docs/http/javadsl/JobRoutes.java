/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

//#route
import java.time.Duration;
import java.util.Optional;
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
  private final ActorSystem<?> system;
  private final ActorRef<JobRepository.Command> buildJobRepository;

  public JobRoutes(ActorRef<JobRepository.Command> buildJobRepository, ActorSystem<?> system) {
    this.system = system;
    this.buildJobRepository = buildJobRepository;
  }

  private Route addOrDelete() {
    return concat(
            post(() ->
                    entity(Jackson.unmarshaller(JobRepository.Job.class), job ->
                      onSuccess(add(job), r -> complete("Job added"))
                    )),
            delete(() -> onSuccess(deleteAll(), r -> complete("Jobs cleared")))
    );
  }

  private CompletionStage<JobRepository.OK> add(JobRepository.Job job) {
    return handleKO(AskPattern.ask(
            buildJobRepository,
            replyTo -> new JobRepository.AddJob(job, replyTo),
            Duration.ofSeconds(3),
            system.scheduler()));
  }

  private CompletionStage<JobRepository.OK> deleteAll() {
    return handleKO(AskPattern.ask(
            buildJobRepository,
            JobRepository.ClearJobs::new,
            Duration.ofSeconds(3),
            system.scheduler()));
  }

  public Route jobRoutes() {
    return pathPrefix("jobs", () ->
            concat(
                    pathEnd(this::addOrDelete),
                    get(() ->
                            path(LONG, jobId ->
                              onSuccess(getJob(jobId), jobOption -> {
                                if (jobOption.isPresent()) {
                                  return complete(StatusCodes.OK, jobOption.get(), Jackson.<JobRepository.Job>marshaller());
                                } else {
                                  return complete(StatusCodes.NOT_FOUND);
                                }
                              })
                            )
                    )
            )
    );
  }

  private CompletionStage<Optional<JobRepository.Job>> getJob(Long jobId) {
    return AskPattern.ask(
            buildJobRepository,
            replyTo -> new JobRepository.GetJobById(jobId, replyTo),
            Duration.ofSeconds(3),
            system.scheduler());
  }

  private CompletionStage<JobRepository.OK> handleKO(CompletionStage<JobRepository.Response> stage) {
    return stage.thenApply(response -> {
      if (response instanceof JobRepository.OK) {
        return (JobRepository.OK)response;
      } else if (response instanceof JobRepository.KO) {
        throw new IllegalStateException(((JobRepository.KO) response).reason);
      } else {
        throw new IllegalStateException("Invalid response");
      }
    });
  }
}
//#route