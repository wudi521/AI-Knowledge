package cn.iocoder.yudao.module.ingestion.store;

import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import cn.iocoder.yudao.module.ingestion.dal.mysql.ChunkMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MySQL 写入(ai_chunk)
 */
@Component
public class MysqlChunkStore {

    @Resource
    private ChunkMapper chunkMapper;

    public void insertChunks(List<ChunkDO> chunks) {
        for (ChunkDO chunk : chunks) {
            chunkMapper.insert(chunk);
        }
    }

}
