package org.remus.giteabot.agent.validation;

import org.mockito.Mockito;
import org.mockito.quality.Strictness;

import java.nio.file.Path;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** Mocked {@link Workspace} handles for tests that only need a directory. */
public final class TestWorkspaces {

    private TestWorkspaces() {
    }

    /** A lenient mock whose {@link Workspace#dir()} is {@code dir}; stub further calls as needed. */
    public static Workspace at(Path dir) {
        Workspace workspace = Mockito.mock(Workspace.class, withSettings().strictness(Strictness.LENIENT));
        when(workspace.dir()).thenReturn(dir);
        return workspace;
    }
}
