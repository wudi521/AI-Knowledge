package cn.iocoder.yudao.module.knowledge.service.review.impl;

import cn.iocoder.yudao.module.knowledge.service.review.ReviewItemService;
import cn.iocoder.yudao.module.knowledge.service.version.AiDocVersionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 审核条目服务(Task 7 填充抽取逻辑, 当前为直通自动发布骨架)
 */
@Slf4j
@Service
public class ReviewItemServiceImpl implements ReviewItemService {

    @Resource
    private AiDocVersionService aiDocVersionService;

    @Override
    public void processAfterParsed(Long versionId) {
        // TODO Task 7: LLM 抽取条目后分流; 当前骨架直接自动发布, 保证链路可用
        aiDocVersionService.publish(versionId);
        log.info("[processAfterParsed][版本 {} 骨架自动发布]", versionId);
    }

    @Override
    public void retryExtract(Long versionId) {
        processAfterParsed(versionId);
    }

}
