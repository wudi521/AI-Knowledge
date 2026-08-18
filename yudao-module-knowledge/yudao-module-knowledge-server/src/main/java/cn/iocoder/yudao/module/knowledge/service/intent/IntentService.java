package cn.iocoder.yudao.module.knowledge.service.intent;

import cn.iocoder.yudao.module.knowledge.controller.admin.intent.vo.IntentSaveReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.intent.vo.IntentUpdateReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.intent.AiIntentDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * AI 意图 Service 接口
 */
public interface IntentService {

    /** 查询知识库下全部意图(含停用) */
    List<AiIntentDO> listByKb(Long kbId);

    /** 查询知识库下启用中的意图(检索分类用) */
    List<AiIntentDO> listEnabledByKb(Long kbId);

    /** 创建意图(来源固定 MANUAL) */
    Long createIntent(@Valid IntentSaveReqVO createReqVO);

    /** 更新意图 */
    void updateIntent(@Valid IntentUpdateReqVO updateReqVO);

    /** 删除意图 */
    void deleteIntent(Long id);

    /**
     * 覆盖式写入 LLM_AUTO 意图(意图总结用): 先逻辑删除该知识库全部 LLM_AUTO, 再批量插入
     * 事务内执行; MANUAL 意图不受影响
     *
     * @param kbId 知识库编号
     * @param intents 新意图(name/description), 其余字段(kbId/source/status)由本方法强制填充
     */
    void replaceAutoIntents(Long kbId, List<AiIntentDO> intents);

}
