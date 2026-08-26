package cn.iocoder.yudao.module.ingestion.service;

import cn.iocoder.yudao.module.ingestion.domain.DomainChunkingPipeline;
import cn.iocoder.yudao.module.ingestion.domain.DomainIngestionAdapter;
import cn.iocoder.yudao.module.ingestion.domain.DomainIngestionRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestServiceDomainPipelineArchitectureTest {

    @Test
    void ingestServiceDependsOnPipelineNotDomainAdapterOrRegistry() {
        Class<?>[] fieldTypes = Arrays.stream(IngestServiceImpl.class.getDeclaredFields())
                .map(Field::getType)
                .toArray(Class<?>[]::new);

        assertTrue(Arrays.asList(fieldTypes).contains(DomainChunkingPipeline.class));
        assertFalse(Arrays.asList(fieldTypes).contains(DomainIngestionAdapter.class));
        assertFalse(Arrays.asList(fieldTypes).contains(DomainIngestionRegistry.class));
    }
}
