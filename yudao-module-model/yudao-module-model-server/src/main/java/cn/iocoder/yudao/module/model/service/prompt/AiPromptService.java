package cn.iocoder.yudao.module.model.service.prompt;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptKeyInfoRespVO;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptPageReqVO;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptSaveReqVO;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptUpdateReqVO;
import cn.iocoder.yudao.module.model.dal.dataobject.prompt.AiPromptDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * AI Prompt Service 接口
 */
public interface AiPromptService {

    /** 创建提示词(新版本, 默认停用) */
    Long createPrompt(@Valid AiPromptSaveReqVO req);

    /** 更新提示词(仅停用版本可编辑) */
    void updatePrompt(@Valid AiPromptUpdateReqVO req);

    /** 全量启用(同 key 其他启用行自动停用) */
    void enablePrompt(Long id);

    /** 灰度启用(需该 key 已有全量启用版本; 同 key 其他灰度行自动回退) */
    void grayEnablePrompt(Long id, List<Long> tenantIds);

    /** 关闭灰度(回到停用) */
    void grayOffPrompt(Long id);

    /** 获得提示词 */
    AiPromptDO getPrompt(Long id);

    /** 删除提示词(逻辑删除; 删除启用行后该 key 回退代码默认) */
    void deletePrompt(Long id);

    /** 分页查询 */
    PageResult<AiPromptDO> getPage(AiPromptPageReqVO reqVO);

    /** 按业务键列出所有版本(版本倒序) */
    List<AiPromptDO> listByKey(String key);

    /** 业务键汇总(key 级别信息) */
    List<AiPromptKeyInfoRespVO> keyList();

}
