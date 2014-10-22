package org.arquillian.spacelift.gradle.git

import java.io.File
import java.util.Collection;
import java.util.logging.Logger;

import org.arquillian.spacelift.execution.ExecutionException
import org.arquillian.spacelift.execution.Tasks;
import org.arquillian.spacelift.process.Command
import org.arquillian.spacelift.process.CommandBuilder
import org.arquillian.spacelift.process.ProcessResult
import org.arquillian.spacelift.process.impl.CommandTool;
import org.arquillian.spacelift.tool.Tool;

/**
 * Fetches branches from remote repository.
 * 
 * When {@link #remote(String)} and {@link #branch(String)} are not set, we fetch all remote branches (git fetch --all).
 * 
 * @author <a href="mailto:smikloso@redhat.com">Stefan Miklosovic</a>
 *
 */
class GitFetchTool extends Tool<File, File> {

    private Logger logger = Logger.getLogger(GitFetchTool.class.getName())

    private String remote

    private String branch

    private String local

    @Override
    protected Collection<String> aliases() {
        ["git_fetch"]
    }

    /**
     * 
     * @param remote, e.g. "origin"
     * @return
     */
    GitFetchTool remote(String remote) {
        this.remote = remote
        this
    }

    /**
     * 
     * @param branch branch from remote to fetch, e.g "myBranch"
     * @return
     */
    GitFetchTool branch(String branch) {
        this.branch = branch
        this
    }

    /**
     * When not set, name of local branch will be same as name of branch under fetching
     * 
     * @param local name of branch which will be fetched from {@link #remote(String)} from branch {@link #branch(String)}
     * @return
     */
    GitFetchTool local(String local) {
        this.local = local
        this
    }

    @Override
    protected File process(File repositoryDir) throws Exception {

        CommandBuilder commandBuilder = new CommandBuilder("git").parameter("fetch")

        if (remote && branch) {
            if (!branch.contains(":")) {
                if (!local) {
                    branch = branch + ":" + branch
                } else {
                    branch = branch + ":" + local
                }
            }

            commandBuilder.parameter(remote).parameter(branch)
        } else {
            commandBuilder.parameter("--all")
        }

        Command command = commandBuilder.build()

        logger.info(command.toString())

        ProcessResult result = null

        try {
            result = Tasks.prepare(CommandTool).workingDir(repositoryDir.getAbsolutePath()).command(command).execute().await()
        } catch (ExecutionException ex) {
            if (result != null) {
                throw new ExecutionException(
                String.format("Committing changes in repository '%s' was not successful. Command '%s', exit code: %s",
                repositoryDir.getAbsolutePath(), command.toString(), result.exitValue()),
                ex)
            }
        }

        repositoryDir
    }
}
