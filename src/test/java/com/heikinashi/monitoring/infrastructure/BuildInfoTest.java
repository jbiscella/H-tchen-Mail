package com.heikinashi.monitoring.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class BuildInfoTest {

    @Test
    void label_is_assembled_from_git_properties() {
        Properties p = new Properties();
        p.setProperty("git.build.version", "0.42.0-alpha");
        p.setProperty("git.commit.id.abbrev", "1a2b3c4");
        p.setProperty("git.branch", "main");
        p.setProperty("git.build.time", "2026-05-18T23:00:00Z");

        assertThat(new BuildInfo(p).label()).isEqualTo("0.42.0-alpha+1a2b3c4 branch=main built=2026-05-18T23:00:00Z");
    }

    @Test
    void missing_properties_fall_back_to_unknown() {
        assertThat(new BuildInfo(new Properties()).label()).isEqualTo("unknown+unknown branch=unknown built=unknown");
    }

    @Test
    void default_constructor_reads_the_classpath_resource_without_throwing() {
        // git-commit-id-maven-plugin writes git.properties during the build,
        // so this resolves to a real label; in its absence it stays "unknown".
        assertThat(new BuildInfo().label()).isNotBlank();
    }
}
