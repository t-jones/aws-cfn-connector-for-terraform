package io.cloudsoft.terraform.template;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

class TerraformInterfaceSSH {
    private final String templateName, serverHostname, sshUsername, sshServerKeyFP, 
        sshClientSecretKeyContents, sshClientSecretKeyFile;

    TerraformInterfaceSSH(TerraformBaseHandler<?> h, String templateName) {
        this.serverHostname = h.getHost();
        // TODO port
        this.sshServerKeyFP = h.getFingerprint();
        this.sshUsername = h.getUsername();        
        this.sshClientSecretKeyContents = h.getSSHKey();
        this.sshClientSecretKeyFile = null;
        this.templateName = templateName;
    }
    
    void createTemplateFromURL(String url) throws IOException {
        runSSHCommand(String.format ("mkdir -p ~/tfdata/'%s'", templateName));
        runSSHCommand(String.format ("cd ~/tfdata/'%s' && wget --output-document=configuration.tf '%s'", templateName, url));
        runSSHCommand(String.format ("cd ~/tfdata/'%s' && terraform init -lock=true -input=false -no-color", templateName));
        runSSHCommand(String.format ("cd ~/tfdata/'%s' && terraform apply -lock=true -input=false -auto-approve -no-color", templateName));
    }

    void updateTemplateFromURL(String url) throws IOException {
        runSSHCommand(String.format ("cd ~/tfdata/'%s' && wget --output-document=configuration.tf '%s'", templateName, url));
        runSSHCommand(String.format ("cd ~/tfdata/'%s' && terraform apply -lock=true -input=false -auto-approve -no-color", templateName));
    }

    void createTemplateFromContents(String contents) throws IOException {
        // TODO
    }
    
    void updateTemplateFromContents(String contents) throws IOException {
        // TODO
    }

    void deleteTemplate() throws IOException {
        runSSHCommand(String.format ("cd ~/tfdata/'%s' && terraform destroy -lock=true -auto-approve -no-color", templateName));
        runSSHCommand(String.format ("rm -rf ~/tfdata/'%s'", templateName));
    }

    private void runSSHCommand(String command) throws IOException {
        System.out.println("DEBUG: @" + serverHostname + "> " + command);

        final SSHClient ssh = new SSHClient();

        ssh.addHostKeyVerifier(sshServerKeyFP);
        ssh.connect(serverHostname);
        Session session = null;
        try {
            ssh.authPublickey(sshUsername, getKeyProvider());
            session = ssh.startSession();
            final Session.Command cmd = session.exec(command);
            String s1 = IOUtils.readFully(cmd.getInputStream()).toString();
            cmd.join(5, TimeUnit.SECONDS);
            String s2 = "exit status: " + cmd.getExitStatus(); // TBD
            // cmd.getErrorStream() // TBD
            //model.setMetricValue(s1); // does not compile
            System.out.println(s1);
            System.out.println(s2);
        } finally {
            try {
                if (session != null) {
                    session.close();
                }
            } catch (IOException e) {
                // do nothing
            }
            ssh.disconnect();
        }
    }
    
    private Iterable<KeyProvider> getKeyProvider() throws IOException {
        List<KeyProvider> result = new ArrayList<KeyProvider>();
        if (sshClientSecretKeyContents!=null && !sshClientSecretKeyContents.isEmpty()) {
            // add key provider from contents
            result.add(new SSHClient().loadKeys(
                sshClientSecretKeyContents,
                // TODO does this work, passing null for pub key? it looks like it should.
                null, 
                null));
        }
        if (sshClientSecretKeyFile!=null && !sshClientSecretKeyFile.isEmpty()) {
            // src/main/resources/privkey works.
            // /home/user/.ssh/privkey works if the file has no passphrase (sshj does
            // not support SSH agent, and there is no SSH agent in AWS anyway).
            // ~/.ssh/privkey does not work.
            // ~user/.ssh/privkey does not work.
            result.add(new SSHClient().loadKeys(sshClientSecretKeyFile));
        }
        return result;
    }
}