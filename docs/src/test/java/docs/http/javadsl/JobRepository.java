/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

//#behavior
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.*;
import com.fasterxml.jackson.annotation.*;

/**
 * Actor for use with the HttpServerWithActorsSample
 */
public class JobRepository extends AbstractBehavior<JobRepository.Command> {

  @JsonFormat
  public static final class Job {
    @JsonProperty("id")
    final Long id;
    @JsonProperty("project-name")
    final String projectName;
    @JsonProperty("status")
    final String status;
    @JsonProperty("duration")
    final Long duration;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public Job(@JsonProperty("id") Long id, @JsonProperty("project-name") String projectName, @JsonProperty("duration") Long duration) {
      this(id, projectName, "Success", duration);
    }
    public Job(Long id, String projectName, String status, Long duration) {
      this.id = id;
      this.projectName = projectName;
      this.status = status;
      this.duration = duration;
    }
  }

  // Successful and failure responses
  interface Response {}

  public static final class OK implements Response {
    private static OK INSTANCE = new OK();

    private OK() {}

    public static OK getInstance() {
      return INSTANCE;
    }
  }

  public static final class KO implements Response {
    final String reason;

    public KO(String reason) {
      this.reason = reason;
    }
  }

  // All possible messages that can be sent to this Behavior
  interface Command {}

  public static final class AddJob implements Command {
    final Job job;
    final ActorRef<Response> replyTo;

    public AddJob(Job job, ActorRef<Response> replyTo) {
      this.job = job;
      this.replyTo = replyTo;
    }
  }

  public static final class GetJobById implements Command {
    final Long id;
    final ActorRef<Optional<Job>> replyTo;

    public GetJobById(Long id, ActorRef<Optional<Job>> replyTo) {
      this.id = id;
      this.replyTo = replyTo;
    }
  }

  public static final class ClearJobs implements Command {
    final ActorRef<Response> replyTo;

    public ClearJobs(ActorRef<Response> replyTo) {
      this.replyTo = replyTo;
    }
  }

  public static Behavior<Command> create() {
    return create(new HashMap<Long, Job>());
  }

  public static Behavior<Command> create(Map<Long, Job> jobs) {
    return Behaviors.setup(ctx -> new JobRepository(ctx, jobs));
  }

  private Map<Long, Job> jobs;

  private JobRepository(ActorContext<Command> context, Map<Long, Job> jobs) {
    super(context);
    this.jobs = jobs;
  }

  // This receive handles all possible incoming messages and keeps the state in the actor
  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder()
            .onMessage(AddJob.class, this::addJob)
            .onMessage(GetJobById.class, this::getJobById)
            .onMessage(ClearJobs.class, this::clearJobs)
            .build();
  }

  private Behavior<Command> addJob(AddJob msg) {
    if (jobs.containsKey(msg.job.id))
      msg.replyTo.tell(new KO("Job already exists"));
    else {
      jobs.put(msg.job.id, msg.job);
      msg.replyTo.tell(OK.getInstance());
    }
    return Behaviors.same();
  }

  private Behavior<Command> getJobById(GetJobById msg) {
    if (jobs.containsKey(msg.id)) {
      msg.replyTo.tell(Optional.of(jobs.get(msg.id)));
    } else {
      msg.replyTo.tell(Optional.empty());
    }
    return Behaviors.same();
  }

  private Behavior<Command> clearJobs(ClearJobs msg) {
    msg.replyTo.tell(OK.getInstance());
    jobs.clear();
    return Behaviors.same();
  }
}
//#behavior