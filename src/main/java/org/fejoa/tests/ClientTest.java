/*
 * Copyright 2015.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.tests;

import junit.framework.TestCase;
import org.fejoa.library.crypto.CryptoSettings;
import org.fejoa.library.support.StorageLib;
import org.fejoa.library2.*;
import org.fejoa.library2.Client;
import org.fejoa.library2.command.*;
import org.fejoa.library2.remote.*;
import org.fejoa.library2.util.LooperThread;
import org.fejoa.server.JettyServer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;


public class ClientTest extends TestCase {
    abstract class TestTask {
        TestTask nextTask;

        public void setNextTask(TestTask nextTask) {
            if (this.nextTask != null)
                finishAndFail("next task already set!");
            this.nextTask = nextTask;
        }

        protected void cleanUp() {

        }

        protected void onTaskPerformed() {
            cleanUp();
            try {
                nextTask.perform(this);
            } catch (Exception e) {
                finishAndFail(e.getMessage());
            }
        }

        abstract protected void perform(TestTask previousTask) throws Exception;
    }

    private class ClientStatus {
        final public String name;
        public boolean firstSync;

        public ClientStatus(String name) {
            this.name = name;
        }
    }

    final static String TEST_DIR = "jettyTest";
    final static String SERVER_TEST_DIR = TEST_DIR + "/Server";
    final static String SERVER_URL = "http://localhost:8080/";
    final static String USER_NAME_1 = "testUser1";
    final static String USER_NAME_2 = "testUser2";
    final static String PASSWORD = "password";

    final private List<String> cleanUpDirs = new ArrayList<>();
    private JettyServer server;
    private Client client1;
    private ClientStatus clientStatus1;
    private Client client2;
    private ClientStatus clientStatus2;
    private LooperThread clientThread = new LooperThread(10);

    private boolean failure = false;
    private Semaphore finishedSemaphore;

    class SimpleObserver implements Task.IObserver<Void, RemoteJob.Result> {
        final private Runnable onSuccess;

        public SimpleObserver(Runnable onSuccess) {
            this.onSuccess = onSuccess;

        }

        @Override
        public void onProgress(Void aVoid) {
            System.out.println("onProgress: ");
        }

        @Override
        public void onResult(RemoteJob.Result result) {
            if (result.status != RemoteJob.Result.DONE)
                finishAndFail(result.message);
            System.out.println("onNext: " + result.message);
            onSuccess.run();
        }

        @Override
        public void onException(Exception exception) {
            finishAndFail(exception.getMessage());
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        finishedSemaphore = new Semaphore(0);

        cleanUpDirs.add(TEST_DIR);
        for (String dir : cleanUpDirs)
            StorageLib.recursiveDeleteFile(new File(dir));

        server = new JettyServer(SERVER_TEST_DIR);
        server.start();

        client1 = new Client(TEST_DIR + "/" + USER_NAME_1);
        client1.getConnectionManager().setStartScheduler(new Task.NewThreadScheduler());
        client1.getConnectionManager().setObserverScheduler(new Task.LooperThreadScheduler(clientThread));
        clientStatus1 = new ClientStatus(USER_NAME_1);

        client2 = new Client(TEST_DIR + "/" + USER_NAME_2);
        client2.getConnectionManager().setStartScheduler(new Task.NewThreadScheduler());
        client2.getConnectionManager().setObserverScheduler(new Task.LooperThreadScheduler(clientThread));
        clientStatus2 = new ClientStatus(USER_NAME_2);

        clientThread.start();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();

        server.stop();

        for (String dir : cleanUpDirs)
            StorageLib.recursiveDeleteFile(new File(dir));
    }

    private void finishAndFail(String message) {
        failure = true;
        finishedSemaphore.release();
        fail(message);
    }

    class FinishTask extends TestTask {
        @Override
        protected void perform(TestTask previousTask) throws Exception {
            client1.stopSyncing();
            client2.stopSyncing();
            finishedSemaphore.release();
        }
    }

    class MergeTask extends TestTask {
        final private List<TestTask> tasks = new ArrayList<>();

        public MergeTask(TestTask... tasks) {
            for (TestTask task : tasks) {
                task.setNextTask(this);
                this.tasks.add(task);
            }
        }

        @Override
        protected void perform(TestTask previousTask) throws Exception {
            if (!tasks.remove(previousTask))
                throw new Exception("Unexpected task");
            if (tasks.size() == 0)
                onTaskPerformed();
        }
    }

    class ContactRequestTask extends TestTask {
        private ContactRequest contactRequest = new ContactRequest(client1);
        private ContactRequest.IHandler handler = new ContactRequest.AutoAcceptHandler() {
            @Override
            public void onFinish() {
                onTaskPerformed();
            }

            @Override
            public void onException(Exception exception) {
                finishAndFail(exception.getMessage());
            }
        };

        @Override
        protected void perform(TestTask previousTask) throws Exception {
            contactRequest.startRequest(USER_NAME_2, SERVER_URL, handler);
        }
    }

    class GrantAccessTask extends TestTask {
        private IncomingCommandManager.IListener listener = new IncomingCommandManager.IListener() {
            @Override
            public void onCommandReceived(IncomingCommandManager.ReturnValue returnValue) {
                if (returnValue.command.equals(AccessCommand.COMMAND_NAME)) {
                    AccessCommandHandler.ReturnValue ret = (AccessCommandHandler.ReturnValue)returnValue;
                    String contactId = ret.contactId;
                    AccessTokenContact accessTokenContact = ret.accessTokenContact;
                    System.out.println("Access granted: " + contactId + " access entry: "
                            + accessTokenContact.getAccessEntry());

                    waitTillClient2UploadedTheAccessStore(0);
                }
            }

            @Override
            public void onException(Exception exception) {
                finishAndFail(exception.getMessage());
            }
        };

        @Override
        protected void perform(TestTask previousTask) throws Exception {
            client1.getIncomingCommandManager().addListener(listener);

            UserData clientUserData = client2.getUserData();
            ContactStore contactStore = clientUserData.getContactStore();
            ContactPublic client2Contact = contactStore.getContactList().get(
                    client1.getUserData().getIdentityStore().getMyself().getId());

            // grant access to the access branch
            client2.grantAccess(clientUserData.getAccessStore().getId(), AccessRight.PULL, client2Contact);
        }

        @Override
        protected void cleanUp() {
            client2.getIncomingCommandManager().removeListener(listener);
        }

        private void waitTillClient2UploadedTheAccessStore(final int retryCount) {
            UserData userData = client2.getUserData();
            client2.peekRemoteStatus(userData.getAccessStore().getId(), new Task.IObserver<Void, WatchJob.Result>() {
                @Override
                public void onProgress(Void aVoid) {

                }

                @Override
                public void onResult(WatchJob.Result result) {
                    if (result.updated.size() == 0) {
                        System.out.println("Access store updated, retry counts: " + retryCount);
                        onTaskPerformed();
                        return;
                    }
                    int count = retryCount + 1;
                    if (count > 10) {
                        finishAndFail("failed to check access store status: too many retries");
                        return;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    waitTillClient2UploadedTheAccessStore(count);
                }

                @Override
                public void onException(Exception exception) {
                    finishAndFail("failed to check access store status");
                }
            });
        }
    }

    class AccessRequest extends TestTask {

        @Override
        protected void perform(TestTask previousTask) throws Exception {
            UserData clientUserData = client1.getUserData();
            ContactStore contactStore = clientUserData.getContactStore();
            ContactPublic client2Contact = contactStore.getContactList().get(
                    client2.getUserData().getIdentityStore().getMyself().getId());

            AccessTokenContact accessTokenContact = client2Contact.getAccessTokenList().getEntries().iterator().next();

            client1.getConnectionManager().submit(new AccessRequestJob(USER_NAME_2, accessTokenContact),
                    new ConnectionManager.ConnectionInfo(USER_NAME_2, SERVER_URL),
                    new ConnectionManager.AuthInfo(ConnectionManager.AuthInfo.NONE, null),
                    new SimpleObserver(new Runnable() {
                        @Override
                        public void run() {
                            onTaskPerformed();
                        }
                    }));
        }
    }

    class CreateAndSyncAccountTask extends TestTask {
        final private Client client;
        final private ClientStatus status;

        CreateAndSyncAccountTask(Client client, ClientStatus status) {
            this.client = client;
            this.status = status;
        }

        @Override
        protected void perform(TestTask previousTask) throws Exception {
            client.create(status.name, SERVER_URL, PASSWORD);
            client.commit();

            client.createAccount(status.name, PASSWORD, SERVER_URL, new SimpleObserver(new Runnable() {
                @Override
                public void run() {
                    try {
                        onAccountCreated(client, status);
                    } catch (Exception e) {
                        finishAndFail(e.getMessage());
                    }
                }
            }));
        }

        private void onAccountCreated(Client client, final ClientStatus status) throws Exception {
            System.out.print("Account Created");
            // watch
            client.startSyncing(new Task.IObserver<TaskUpdate, Void>() {
                @Override
                public void onProgress(TaskUpdate update) {
                    System.out.println(update.toString());
                    if (!status.firstSync && update.getTotalWork() > 0 && update.getProgress() == update.getTotalWork()) {
                        status.firstSync = true;
                        startCommandManagers();
                        onTaskPerformed();
                    }
                }

                @Override
                public void onResult(Void aVoid) {
                    System.out.println(status.name + ": sync ok");
                }

                @Override
                public void onException(Exception exception) {
                    finishAndFail(exception.getMessage());
                }
            });
        }

        private void startCommandManagers() {
            client.startCommandManagers(new Task.IObserver<TaskUpdate, Void>() {
                @Override
                public void onProgress(TaskUpdate update) {
                    System.out.println(status.name + ": " + update.toString());
                }

                @Override
                public void onResult(Void aVoid) {
                    System.out.println(status.name + ": Command sent");
                }

                @Override
                public void onException(Exception exception) {
                    finishAndFail(exception.getMessage());
                }
            });
        }
    }

    private void chainUpTasks(TestTask... tasks) {
        for (int i = 0; i < tasks.length - 1; i++)
            tasks[i].setNextTask(tasks[i + 1]);
    }

    public void testClient() throws Exception {
        CreateAndSyncAccountTask createAccountTask1 = new CreateAndSyncAccountTask(client1, clientStatus1);
        CreateAndSyncAccountTask createAccountTask2 = new CreateAndSyncAccountTask(client2, clientStatus2);

        // start it
        chainUpTasks(new MergeTask(createAccountTask1, createAccountTask2),
                new ContactRequestTask(),
                new GrantAccessTask(),
                new AccessRequest(),
                new FinishTask());

        createAccountTask1.perform(null);
        createAccountTask2.perform(null);

        finishedSemaphore.acquire();
        assertFalse(failure);
        Thread.sleep(1000);
        clientThread.quit(true);
    }
}
