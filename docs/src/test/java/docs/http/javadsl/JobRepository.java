package docs.http.javadsl;

//#behavior
import java.util.Collections;
import java.util.Map;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.*;

/**
 * Actor for use with the HttpServerWithActorsSample
 */
public class JobRepository extends AbstractBehavior<JobRepository.Command> {
  // Definition of the a build job and its possible status values
  interface Status {
  }

  public static final class Successful implements Status {
  }

  public static final class Failed implements Status {
  }

  public static final class Job {
    final Long id;
    final String projectName;
    final Status status;
    final Long duration;

    public Job(Long id, String projectName, Status status, Long duration) {
      this.id = id;
      this.projectName = projectName;
      this.status = status;
      this.duration = duration;
    }
  }

  // Successful and failure responses
  interface Response {
  }

  public static final class OK implements Response {
    private static OK INSTANCE = new OK();

    private OK() {
    }

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
  interface Command {
  }

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
    final ActorRef<Job> replyTo;

    public GetJobById(Long id, ActorRef<Job> replyTo) {
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
    return create(Collections.emptyMap());
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
      msg.replyTo.tell(OK.getInstance());
      jobs.put(msg.job.id, msg.job);
    }
    return Behaviors.same();
  }

  private Behavior<Command> getJobById(GetJobById msg) {
    msg.replyTo.tell(jobs.get(msg.id));
    return Behaviors.same();
  }

  private Behavior<Command> clearJobs(ClearJobs msg) {
    msg.replyTo.tell(OK.getInstance());
    jobs.clear();
    return Behaviors.same();
  }
}
//#behavior