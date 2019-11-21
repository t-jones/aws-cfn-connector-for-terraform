package io.cloudsoft.terraform.infrastructure;

import io.cloudsoft.terraform.infrastructure.worker.AbstractHandlerWorker;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.xfer.InMemorySourceFile;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class TerraformInterfaceSSH {
    protected final Logger logger;
    protected final String configurationIdentifier, serverHostname, sshUsername, sshServerKeyFP,
            sshClientSecretKeyContents;
    protected final int sshPort;
    protected String lastStdout, lastStderr;
    protected Integer lastExitStatusOrNull;

    // Convert these constants to parameters later if necessary (more likely to be
    // useful after parameters can be specified separately for each server).
    private static final String
            // TF_DATADIR must match the contents of the files in server-side-systemd/
            // (at least as far as realpath(1) is concerned).
            // sshj does not expand tilde to the remote user's home directory on the server
            // (OpenSSH scp does that). Also neither any directory components nor the
            // file name can be quoted (as in "/some/'work dir'/otherdir") because sshj
            // fails to escape the quotes properly (again, works in OpenSSH).
            TF_DATADIR = "~/tfdata",
            TF_SCPDIR = "/tmp",
            TF_TMPFILENAME = "configuration.bin",
            TF_CONFFILENAME = "configuration.tf";

    protected TerraformInterfaceSSH(TerraformBaseHandler<?> h, Logger logger, AmazonWebServicesClientProxy proxy, String configurationIdentifier) {
        this.logger = logger;
        this.serverHostname = h.getHost(proxy);
        this.sshPort = h.getPort(proxy);
        this.sshServerKeyFP = h.getFingerprint(proxy);
        this.sshUsername = h.getUsername(proxy);
        this.sshClientSecretKeyContents = h.getSSHKey(proxy);
        this.configurationIdentifier = configurationIdentifier;
    }

    public String getWorkdir() {
        return String.format("%s/%s", TF_DATADIR, configurationIdentifier);
    }

    private String getScpDir() {
        return String.format("%s/%s", TF_SCPDIR, configurationIdentifier);
    }

    public void onlyMkdir() throws IOException {
        onlyMkdir(getWorkdir());
    }

    public void onlyMkdir(String dir) throws IOException {
        runSSHCommand("mkdir -p " + dir);
    }

    public void onlyMv(String source, String target) throws IOException {
        runSSHCommand(String.format("mv %s %s", source, target));
    }

    public void onlyRmdir() throws IOException {
        onlyRmdir(getWorkdir());
    }

    public void onlyRmdir(String dir) throws IOException {
        runSSHCommand("rm -rf " + dir);
    }

    protected void debug(String message) {
        System.out.println(message);
        logger.log(message);
    }

    protected void runSSHCommand(String command) throws IOException {
        debug("DEBUG: @" + serverHostname + "> " + command);

        final SSHClient ssh = new SSHClient();

        ssh.addHostKeyVerifier(sshServerKeyFP);
        ssh.connect(serverHostname, sshPort);
        Session session = null;
        try {
            ssh.authPublickey(sshUsername, ssh.loadKeys(sshClientSecretKeyContents, null, null));
            session = ssh.startSession();
            final Session.Command cmd = session.exec(command);
            cmd.join(30, TimeUnit.SECONDS);
            lastExitStatusOrNull = cmd.getExitStatus();
            lastStdout = IOUtils.readFully(cmd.getInputStream()).toString();
            lastStderr = IOUtils.readFully(cmd.getErrorStream()).toString();
            debug("stdout: " + lastStdout);
            debug("stderr: " + lastStderr);
            debug("exit status: " + lastExitStatusOrNull);
            if (!((Integer) 0).equals(lastExitStatusOrNull) || !lastStderr.isEmpty()) {
                logger.log("Unexpected result/output from command '" + command + "': " + lastExitStatusOrNull + "\n"
                        + "  stderr: " + lastStderr + "\n"
                        + "  stdout: " + lastStdout);
            }
        } finally {
            try {
                if (session != null) {
                    session.close();
                }
            } catch (IOException e) {
                // do nothing
            }
            try {
                ssh.disconnect();
                ssh.close();
            } catch (IOException e) {
                // do nothing
            }
        }
    }

    public String getLastStdout() {
        return lastStdout;
    }

    public String getLastStderr() {
        return lastStderr;
    }

    public Integer getLastExitStatusOrNull() {
        return lastExitStatusOrNull;
    }

    public void uploadConfiguration(byte[] contents) throws IOException, IllegalArgumentException {
        onlyMkdir(getScpDir());
        uploadFile(getScpDir(), TF_TMPFILENAME, contents);
        String tmpFilename = getScpDir() + "/" + TF_TMPFILENAME;
        runSSHCommand("file  --brief --mime-type " + tmpFilename);
        String mimeType = lastStdout.replaceAll("\n", "");

        switch (mimeType) {
            case "text/plain":
                onlyMv(tmpFilename, getWorkdir() + "/" + TF_CONFFILENAME);
                break;
            case "application/zip":
                runSSHCommand(String.format("unzip %s -d %s", tmpFilename, getWorkdir()));
                break;
            default:
                onlyRmdir(getScpDir());
                throw new IllegalArgumentException("Unknown MIME type " + mimeType);
        }
        onlyRmdir(getScpDir());
    }

    public void uploadFile(String dirName, String fileName, byte[] contents) throws IOException {
        BytesSourceFile src = new BytesSourceFile(fileName, contents);
        SSHClient ssh = new SSHClient();
        ssh.addHostKeyVerifier(sshServerKeyFP);
        ssh.connect(serverHostname, sshPort);
        try {
            ssh.authPublickey(sshUsername, ssh.loadKeys(sshClientSecretKeyContents, null, null));
            ssh.newSCPFileTransfer().upload(src, dirName);
        } finally {
            try {
                ssh.disconnect();
                ssh.close();
            } catch (Exception ee) {
                // ignore
            }
        }
    }

    private static class BytesSourceFile extends InMemorySourceFile {
        private String name;
        private byte[] contents;

        BytesSourceFile(String name, byte[] contents) {
            this.name = name;
            this.contents = contents;
        }

        public String getName() {
            return name;
        }

        public long getLength() {
            return contents.length;
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(contents);
        }
    }

    public static TerraformInterfaceSSH of(AbstractHandlerWorker w) {
        return new TerraformInterfaceSSH(w.handler, w.logger, w.proxy, w.model.getIdentifier());
    }
}
