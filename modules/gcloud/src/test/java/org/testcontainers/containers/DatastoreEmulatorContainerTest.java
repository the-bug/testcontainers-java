package org.testcontainers.containers;

import com.google.cloud.NoCredentials;
import com.google.cloud.ServiceOptions;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.Key;
import org.junit.Rule;
import org.junit.Test;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class DatastoreEmulatorContainerTest {

    @Rule
    // creatingDatastoreEmulatorContainer {
    public DatastoreEmulatorContainer emulator = new DatastoreEmulatorContainer(
        DockerImageName.parse("gcr.io/google.com/cloudsdktool/cloud-sdk:367.0.0-emulators")
    );

    // }

    //  startingDatastoreEmulatorContainer {
    @Test
    public void testSimple() {
        DatastoreOptions options = DatastoreOptions
            .newBuilder()
            .setHost(emulator.getEmulatorEndpoint())
            .setCredentials(NoCredentials.getInstance())
            .setRetrySettings(ServiceOptions.getNoRetrySettings())
            .setProjectId("test-project")
            .build();
        Datastore datastore = options.getService();

        Key key = datastore.newKeyFactory().setKind("Task").newKey("sample");
        Entity entity = Entity.newBuilder(key).set("description", "my description").build();
        datastore.put(entity);

        assertThat(datastore.get(key).getString("description")).isEqualTo("my description");
    }

    // }

    @Test
    public void testWithFlags() throws IOException, InterruptedException {
        try (
            DatastoreEmulatorContainer emulator = new DatastoreEmulatorContainer(
                "gcr.io/google.com/cloudsdktool/cloud-sdk:367.0.0-emulators"
            )
                .withFlags("--consistency 1.0")
        ) {
            emulator.start();

            assertThat(emulator.getContainerInfo().getConfig().getCmd()).anyMatch(e -> e.contains("--consistency 1.0"));
            assertThat(emulator.execInContainer("ls", "/root/.config/").getStdout()).contains("gcloud");
        }
    }

    @Test
    public void testWithMultipleFlags() throws IOException, InterruptedException {
        try (
            DatastoreEmulatorContainer emulator = new DatastoreEmulatorContainer(
                "gcr.io/google.com/cloudsdktool/cloud-sdk:367.0.0-emulators"
            )
                .withFlags("--consistency 1.0 --data-dir /root/.config/test-gcloud")
        ) {
            emulator.start();

            assertThat(emulator.getContainerInfo().getConfig().getCmd()).anyMatch(e -> e.contains("--consistency 1.0"));
            assertThat(emulator.execInContainer("ls", "/root/.config/").getStdout()).contains("test-gcloud");
        }
    }
}
