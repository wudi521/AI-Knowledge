package cn.iocoder.yudao.module.eval.api;

import cn.iocoder.yudao.module.eval.api.EvalApi;
import org.springframework.stereotype.Service;

/**
 * 评测平台 对外 RPC 实现
 */
@Service
public class EvalApiImpl implements EvalApi {

    @Override
    public Boolean runEval(Long taskId) {
    return false;

}
