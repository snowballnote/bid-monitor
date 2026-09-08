package com.comhu.bidmonitor.performance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NasPerformanceFileContentAdapterTests {
    @TempDir Path temp;
    private JdbcTemplate jdbc;
    private ObjectProvider<JdbcTemplate> provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Local H2 fixture only: never a company datasource.
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE public.files(file_id BIGINT, storage_path VARCHAR(2000), file_status INT, is_dir BOOLEAN)");
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
    }

    @Test
    void readsExistingMountedFileWithoutModifyingIt() throws Exception {
        Path root = Files.createDirectory(temp.resolve("nas"));
        Path file = Files.writeString(root.resolve("proof.pdf"), "contents");
        jdbc.update("INSERT INTO public.files VALUES(1, 'proof.pdf', 0, false)");
        try (var input = new NasPerformanceFileContentAdapter(provider, root.toString()).open(1)) {
            assertThat(new String(input.readAllBytes())).isEqualTo("contents");
        }
        assertThat(Files.readString(file)).isEqualTo("contents");
    }

    @Test
    void rejectsTraversalAbsoluteOutsidePathAndInactiveFileWithSafeError() throws Exception {
        Path root = Files.createDirectory(temp.resolve("nas"));
        Path outside = Files.writeString(temp.resolve("secret.pdf"), "secret");
        jdbc.update("INSERT INTO public.files VALUES(1, '../secret.pdf', 0, false)");
        jdbc.update("INSERT INTO public.files VALUES(2, ?, 0, false)", outside.toString());
        jdbc.update("INSERT INTO public.files VALUES(3, 'proof.pdf', 1, false)");
        jdbc.update("INSERT INTO public.files VALUES(4, '.', 0, true)");
        var adapter = new NasPerformanceFileContentAdapter(provider, root.toString());
        for (long id = 1; id <= 5; id++) {
            long fileId = id;
            assertThatThrownBy(() -> adapter.open(fileId)).isInstanceOf(IOException.class)
                    .hasMessageNotContaining("secret").hasMessageNotContaining(temp.toString()).hasNoCause();
        }
        assertThat(Files.readString(outside)).isEqualTo("secret");
    }

    @Test
    void disabledMountDoesNotQueryCompanyDatabase() {
        var adapter = new NasPerformanceFileContentAdapter(provider, "");
        assertThatThrownBy(() -> adapter.open(1)).isInstanceOf(IOException.class).hasNoCause();
        verifyNoInteractions(provider);
    }
}