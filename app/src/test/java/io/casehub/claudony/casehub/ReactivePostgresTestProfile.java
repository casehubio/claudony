package io.casehub.claudony.casehub;

import io.quarkus.test.junit.QuarkusTestProfile;

public class ReactivePostgresTestProfile implements QuarkusTestProfile {

    @Override
    public String getConfigProfile() {
        return "reactive-pg";
    }
}
