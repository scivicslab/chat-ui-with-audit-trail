package com.scivicslab.sshconnector;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.sshtools.SshCommandLine;
import com.scivicslab.sshtools.SshConfigFile;
import com.scivicslab.sshtools.SshRunner;
import com.scivicslab.sshtools.SshTools;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.logging.Logger;

/**
 * ssh-connector's beans (tasks/todo.md, stage 11): its settings, its one scope with the ssh tools, and the actor system
 * the host's {@link SshRunner} runs in. The scope is made when the MCP server registers its tools at start-up, and it
 * writes ssh_config and known_hosts then.
 */
@ApplicationScoped
public class ConnectorBeans {

    private static final Logger LOG = Logger.getLogger(ConnectorBeans.class.getName());

    @ConfigProperty(name = "ssh-connector.dir")
    String dir;

    @Produces
    @Singleton
    ConnectorSettings settings() {
        Config config = ConfigProvider.getConfig();
        return ConnectorSettings.from(Path.of(dir), key -> config.getOptionalValue(key, String.class));
    }

    @Produces
    @Singleton
    ActorSystem actorSystem() {
        return new ActorSystem("ssh-connector");
    }

    void close(@Disposes ActorSystem system) {
        system.terminate();
    }

    @Produces
    @Singleton
    ConnectorScope scope(ConnectorSettings settings, ActorSystem system) {
        try {
            Files.createDirectories(settings.privateDir(),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.createDirectories(settings.workDir());
            Path config = SshConfigFile.write(settings.sshDir(), settings.hostEntries());
            String alias = settings.host().alias();
            ActorRef<SshRunner> runner = system.actorOf("ssh-" + alias, new SshRunner(settings.limits()));
            FileAccessScope files = new FileAccessScope(settings.workDir(), List.of());
            SshTools tools = new SshTools(runner, new SshCommandLine(settings.ssh(), config, alias), settings.slurm(),
                    settings.limits(), files, SshTools::newJobDirName);
            LOG.info("ssh tools for " + alias + " with " + config);
            return new ConnectorScope(tools, SshTools.specs(), files);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
