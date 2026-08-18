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

}
