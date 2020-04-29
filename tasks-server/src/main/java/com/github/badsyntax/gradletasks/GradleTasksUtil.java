package com.github.badsyntax.gradletasks;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import com.google.common.base.Strings;
import org.gradle.tooling.BuildCancelledException;
import org.gradle.tooling.BuildException;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.CancellationTokenSource;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProgressEvent;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.UnsupportedVersionException;
import org.gradle.tooling.exceptions.UnsupportedBuildArgumentException;
import org.gradle.tooling.model.build.BuildEnvironment;
import io.grpc.stub.StreamObserver;

public class GradleTasksUtil {

  private static final CancellationTokenPool cancellationTokenPool = new CancellationTokenPool();
  private static final Object lock = new Object();
  private static final String JAVA_TOOL_OPTIONS_ENV = "JAVA_TOOL_OPTIONS";

  private GradleTasksUtil() {
  }

  public static void getBuild(final File projectDir, final GetBuildRequest req,
      final StreamObserver<GetBuildReply> responseObserver) throws GradleTasksException {
    CancellationTokenSource cancellationTokenSource = GradleConnector.newCancellationTokenSource();
    GradleConnector gradleConnector =
        GradleConnector.newConnector().forProjectDirectory(projectDir);
    if (!Strings.isNullOrEmpty(req.getGradleUserHome())) {
      gradleConnector
          .useGradleUserHomeDir(buildGradleUserHomeFile(req.getGradleUserHome(), projectDir));
    }
    String cancellationKey = projectDir.getAbsolutePath();
    cancellationTokenPool.put(CancellationTokenPool.TYPE.GET, cancellationKey,
        cancellationTokenSource);
    try (ProjectConnection connection = gradleConnector.connect()) {
      responseObserver.onNext(buildEnvironmentReply(connection));

      ModelBuilder<org.gradle.tooling.model.GradleProject> rootProjectBuilder =
          connection.model(org.gradle.tooling.model.GradleProject.class);
      rootProjectBuilder.withCancellationToken(cancellationTokenSource.token())
          .addProgressListener((ProgressEvent progressEvent) -> {
            synchronized (lock) {
              Progress progress =
                  Progress.newBuilder().setMessage(progressEvent.getDescription()).build();
              GetBuildReply reply = GetBuildReply.newBuilder().setProgress(progress).build();
              responseObserver.onNext(reply);
            }
          }).setStandardOutput(new GradleOutputListener() {
            @Override
            public final void onOutputChanged(ByteArrayOutputStream outputMessage) {
              synchronized (lock) {
                Output output = Output.newBuilder().setOutputType(Output.OutputType.STDOUT)
                    .setMessage(outputMessage.toString()).build();
                GetBuildReply reply = GetBuildReply.newBuilder().setOutput(output).build();
                responseObserver.onNext(reply);
              }
            }
          }).setStandardError(new GradleOutputListener() {
            @Override
            public final void onOutputChanged(ByteArrayOutputStream outputMessage) {
              synchronized (lock) {
                Output output = Output.newBuilder().setOutputType(Output.OutputType.STDERR)
                    .setMessage(outputMessage.toString()).build();
                GetBuildReply reply = GetBuildReply.newBuilder().setOutput(output).build();
                responseObserver.onNext(reply);
              }
            }
          }).setColorOutput(false);
      GradleProject gradleProject = buildProject(rootProjectBuilder.get());
      GradleBuild gradleBuild = GradleBuild.newBuilder().setProject(gradleProject).build();
      GetBuildResult result = GetBuildResult.newBuilder().setBuild(gradleBuild).build();
      GetBuildReply reply = GetBuildReply.newBuilder().setGetBuildResult(result).build();
      responseObserver.onNext(reply);
    } catch (BuildCancelledException e) {
      Cancelled cancelled = Cancelled.newBuilder().setMessage(e.getMessage())
          .setProjectDir(projectDir.getPath()).build();
      GetBuildReply reply = GetBuildReply.newBuilder().setCancelled(cancelled).build();
      responseObserver.onNext(reply);
    } catch (BuildException | UnsupportedVersionException | UnsupportedBuildArgumentException
        | IllegalStateException e) {
      throw new GradleTasksException(e.getMessage(), e);
    } catch (RuntimeException err) {
      throw new GradleTasksException(err.getMessage(), err);
    } finally {
      cancellationTokenPool.remove(CancellationTokenPool.TYPE.GET, cancellationKey);
    }
  }

  public static void runTask(final File projectDir, final RunTaskRequest req,
      final StreamObserver<RunTaskReply> responseObserver) throws GradleTasksException {
    GradleConnector gradleConnector =
        GradleConnector.newConnector().forProjectDirectory(projectDir);
    if (!Strings.isNullOrEmpty(req.getGradleUserHome())) {
      gradleConnector
          .useGradleUserHomeDir(buildGradleUserHomeFile(req.getGradleUserHome(), projectDir));
    }
    CancellationTokenSource cancellationTokenSource = GradleConnector.newCancellationTokenSource();
    String cancellationKey = projectDir.getAbsolutePath() + req.getTask();
    cancellationTokenPool.put(CancellationTokenPool.TYPE.RUN, cancellationKey,
        cancellationTokenSource);
    try (ProjectConnection connection = gradleConnector.connect()) {
      BuildLauncher build =
          connection.newBuild().withCancellationToken(cancellationTokenSource.token())
              .addProgressListener((ProgressEvent progressEvent) -> {
                synchronized (lock) {
                  Progress progress =
                      Progress.newBuilder().setMessage(progressEvent.getDescription()).build();
                  RunTaskReply reply = RunTaskReply.newBuilder().setProgress(progress).build();
                  responseObserver.onNext(reply);
                }
              }).setStandardOutput(new GradleOutputListener() {
                @Override
                public final void onOutputChanged(ByteArrayOutputStream outputMessage) {
                  synchronized (lock) {
                    Output output = Output.newBuilder().setOutputType(Output.OutputType.STDOUT)
                        .setMessage(outputMessage.toString()).build();
                    RunTaskReply reply = RunTaskReply.newBuilder().setOutput(output).build();
                    responseObserver.onNext(reply);
                  }
                }
              }).setStandardError(new GradleOutputListener() {
                @Override
                public final void onOutputChanged(ByteArrayOutputStream outputMessage) {
                  synchronized (lock) {
                    Output output = Output.newBuilder().setOutputType(Output.OutputType.STDERR)
                        .setMessage(outputMessage.toString()).build();
                    RunTaskReply reply = RunTaskReply.newBuilder().setOutput(output).build();
                    responseObserver.onNext(reply);
                  }
                }
              }).setColorOutput(true).withArguments(req.getArgsList()).forTasks(req.getTask());
      if (Boolean.TRUE.equals(req.getJavaDebug())) {
        build.setEnvironmentVariables(buildJavaEnvVarsWithJwdp(req.getJavaDebugPort()));
      }
      build.run();
      RunTaskResult result = RunTaskResult.newBuilder().setMessage("Successfully run task")
          .setTask(req.getTask()).build();
      RunTaskReply reply = RunTaskReply.newBuilder().setRunTaskResult(result).build();
      responseObserver.onNext(reply);
    } catch (BuildException | UnsupportedVersionException | UnsupportedBuildArgumentException
        | IllegalStateException e) {
      throw new GradleTasksException(e.getMessage(), e);
    } catch (BuildCancelledException e) {
      Cancelled cancelled = Cancelled.newBuilder().setMessage(e.getMessage()).setTask(req.getTask())
          .setProjectDir(projectDir.getPath()).build();
      RunTaskReply reply = RunTaskReply.newBuilder().setCancelled(cancelled).build();
      responseObserver.onNext(reply);
    } finally {
      cancellationTokenPool.remove(CancellationTokenPool.TYPE.RUN, cancellationKey);
    }
  }

  public static void cancelGetBuilds(StreamObserver<CancelGetBuildsReply> responseObserver) {
    Map<String, CancellationTokenSource> pool =
        cancellationTokenPool.getPoolType(CancellationTokenPool.TYPE.GET);
    pool.keySet().stream().forEach(key -> pool.get(key).cancel());
    CancelGetBuildsReply reply =
        CancelGetBuildsReply.newBuilder().setMessage("Cancel get projects requested").build();
    responseObserver.onNext(reply);
  }

  public static void cancelRunTask(File projectDir, String task,
      StreamObserver<CancelRunTaskReply> responseObserver) {
    String cancellationKey = projectDir.getAbsolutePath() + task;
    CancellationTokenSource cancellationTokenSource =
        cancellationTokenPool.get(CancellationTokenPool.TYPE.RUN, cancellationKey);
    if (cancellationTokenSource != null) {
      cancellationTokenSource.cancel();
      CancelRunTaskReply reply = CancelRunTaskReply.newBuilder()
          .setMessage("Cancel run task requested").setTaskRunning(true).build();
      responseObserver.onNext(reply);
    } else {
      CancelRunTaskReply reply = CancelRunTaskReply.newBuilder().setMessage("Task is not running")
          .setTaskRunning(false).build();
      responseObserver.onNext(reply);
    }
  }

  public static void cancelRunTasks(StreamObserver<CancelRunTasksReply> responseObserver) {
    Map<String, CancellationTokenSource> pool =
        cancellationTokenPool.getPoolType(CancellationTokenPool.TYPE.RUN);
    pool.keySet().stream().forEach(key -> pool.get(key).cancel());
    CancelRunTasksReply reply =
        CancelRunTasksReply.newBuilder().setMessage("Cancel run tasks requested").build();
    responseObserver.onNext(reply);
  }

  private static GradleProject buildProject(org.gradle.tooling.model.GradleProject gradleProject) {
    GradleProject.Builder project =
        GradleProject.newBuilder().setIsRoot(gradleProject.getParent() == null);
    gradleProject.getChildren().stream()
        .forEach(childGradleProject -> project.addProjects(buildProject(childGradleProject)));
    gradleProject.getTasks().stream().forEach(task -> {
      GradleTask.Builder gradleTask = GradleTask.newBuilder()
          .setProject(task.getProject().getName()).setName(task.getName()).setPath(task.getPath())
          .setBuildFile(task.getProject().getBuildScript().getSourceFile().getAbsolutePath())
          .setRootProject(gradleProject.getName());
      if (task.getDescription() != null) {
        gradleTask.setDescription(task.getDescription());
      }
      if (task.getGroup() != null) {
        gradleTask.setGroup(task.getGroup());
      }
      project.addTasks(gradleTask.build());
    });
    return project.build();
  }

  private static Map<String, String> buildJavaEnvVarsWithJwdp(int javaDebugPort) {
    HashMap<String, String> envVars = new HashMap<>(System.getenv());
    envVars.put(JAVA_TOOL_OPTIONS_ENV,
        String.format("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:%d",
            javaDebugPort));
    return envVars;
  }

  private static File buildGradleUserHomeFile(String gradleUserHome, File projectDir) {
    String gradleUserHomePath = Paths.get(gradleUserHome).isAbsolute() ? gradleUserHome
        : Paths.get(projectDir.getAbsolutePath(), gradleUserHome).toAbsolutePath().toString();
    return new File(gradleUserHomePath);
  }

  private static GetBuildReply buildEnvironmentReply(ProjectConnection connection) {
    BuildEnvironment environment = connection.model(BuildEnvironment.class).get();
    org.gradle.tooling.model.build.GradleEnvironment gradleEnvironment = environment.getGradle();
    org.gradle.tooling.model.build.JavaEnvironment javaEnvironment = environment.getJava();
    return GetBuildReply.newBuilder()
        .setEnvironment(Environment.newBuilder()
            .setGradleEnvironment(GradleEnvironment.newBuilder()
                .setGradleUserHome(gradleEnvironment.getGradleUserHome().getAbsolutePath())
                .setGradleVersion(gradleEnvironment.getGradleVersion()))
            .setJavaEnvironment(JavaEnvironment.newBuilder()
                .setJavaHome(javaEnvironment.getJavaHome().getAbsolutePath())
                .addAllJvmArgs(javaEnvironment.getJvmArguments())))
        .build();
  }
}
