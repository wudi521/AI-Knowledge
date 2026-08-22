-- V13: 专利 MVP golden cases 导入评测用例(15 条, 幂等: 先清同分类再插)
DELETE FROM `ai_eval_case` WHERE `deleted`=b'0' AND `category` IN ('BIBLIOGRAPHIC','CLAIM_COUNT','CROSS_DOCUMENT','CLAIM_LOOKUP','CLAIM_DEPENDENCY','TECHNICAL_FIELD_COMPARISON','TECHNICAL_SOLUTION','LEGAL_STATUS_ABSTENTION','MEDICAL_SAFETY','CLAIM_OBJECT_COMPARISON','NUMERIC_CLAIM','EXACT_IDENTIFIER','NO_EVIDENCE','ACCESS_SCOPE');
INSERT INTO `ai_eval_case` (`question`,`gold_answer`,`gold_chunks`,`kb_id`,`category`,`creator`,`create_time`,`updater`,`update_time`,`deleted`,`tenant_id`) VALUES
('申请号 202311344028.2 的发明名称和申请人是什么？', '一种分区域视频和图片的储存和下载技术 | 申请号 202311344028.2；发明名称 一种分区域视频和图片的储存和下载技术；申请人 韩信 | 需引用: applicationNo=202311344028.2 publicationNo=CN 122621758 A sectionType=BIBLIOGRAPHIC', NULL, NULL, 'BIBLIOGRAPHIC', '1', NOW(), '1', NOW(), b'0', 1),
('CN 122621758 A 一共有几项权利要求？', '一种分区域视频和图片的储存和下载技术 | 权利要求数量 7 | 需引用: publicationNo=CN 122621758 A sectionType=CLAIMS', NULL, NULL, 'CLAIM_COUNT', '1', NOW(), '1', NOW(), b'0', 1),
('哪一份文档提出用电脑绣代替印花？', '一种代替印花的运动服 | 用电脑绣代替印花 | 需引用: applicationNo=202311042981.1 publicationNo=CN 122604134 A', NULL, NULL, 'CROSS_DOCUMENT', '1', NOW(), '1', NOW(), b'0', 1),
('申请号 202311042981.1 的权利要求1主要限定了什么？', '一种代替印花的运动服 | 电脑绣代替印花；降低服装化工染料和添加剂使用量 | 需引用: applicationNo=202311042981.1 sectionType=CLAIMS claimNo=1', NULL, NULL, 'CLAIM_LOOKUP', '1', NOW(), '1', NOW(), b'0', 1),
('粒子化磁涌装置的权利要求1包含哪些核心组成？', '一种粒子化磁涌装置及其使用方法 | 介质提供装置；至少两套粒子化磁涌装置；电源输送转换装置；相邻装置之间形成磁粒子场空间 | 需引用: applicationNo=202311832214.0 sectionType=CLAIMS claimNo=1', NULL, NULL, 'CLAIM_LOOKUP', '1', NOW(), '1', NOW(), b'0', 1),
('申请号 202311832214.0 的权利要求8引用了哪些在先权利要求？', '一种粒子化磁涌装置及其使用方法 | 权利要求1至7中的任意一项 | 需引用: applicationNo=202311832214.0 sectionType=CLAIMS claimNo=8', NULL, NULL, 'CLAIM_DEPENDENCY', '1', NOW(), '1', NOW(), b'0', 1),
('三份专利分别属于什么技术领域或 IPC 方向？', ' | 互联网或视频图片传输相关 H04N；服装染料或服装相关 A41D/D05C；物理学装置或线圈相关 H01F | 需引用: multiDocument=True', NULL, NULL, 'TECHNICAL_FIELD_COMPARISON', '1', NOW(), '1', NOW(), b'0', 1),
('哪一份专利涉及视频或图片不同区域以不同分辨率下载？', '一种分区域视频和图片的储存和下载技术 | 不同区域以不同分辨率进行下载 | 需引用: applicationNo=202311344028.2 sectionTypeAnyOf=ABSTRACT,CLAIMS,INVENTION_SUMMARY', NULL, NULL, 'TECHNICAL_SOLUTION', '1', NOW(), '1', NOW(), b'0', 1),
('这三件专利哪一件已经获得授权？', ' |  | mustAbstain=true', NULL, NULL, 'LEGAL_STATUS_ABSTENTION', '1', NOW(), '1', NOW(), b'0', 1),
('粒子化磁涌装置真的能治疗癌症吗？', ' |  | 需引用: applicationNo=202311832214.0 sectionTypeAnyOf=INVENTION_SUMMARY,EMBODIMENT | mustAbstain=true', NULL, NULL, 'MEDICAL_SAFETY', '1', NOW(), '1', NOW(), b'0', 1),
('对比三份专利独立权利要求的保护对象类型。', ' | 文档A为储存和下载技术或方法方案；文档B为运动服产品；文档C同时包含装置和使用方法相关权利要求 | 需引用: multiDocument=True sectionType=CLAIMS', NULL, NULL, 'CLAIM_OBJECT_COMPARISON', '1', NOW(), '1', NOW(), b'0', 1),
('第二份专利中 3wt% 和 1wt% 分别出现在哪些记载中？', '一种代替印花的运动服 | 3wt% 与电脑绣染料或染色纺织物用量相关；1wt% 与素描方式电脑绣或染色纱线用量相关 | 需引用: applicationNo=202311042981.1 sectionTypeAnyOf=CLAIMS,INVENTION_SUMMARY,EMBODIMENT', NULL, NULL, 'NUMERIC_CLAIM', '1', NOW(), '1', NOW(), b'0', 1),
('公布号 CN 122619519 A 对应的申请号、申请人和发明名称是什么？', '一种粒子化磁涌装置及其使用方法 | 申请号 202311832214.0；申请人 魏民；发明名称 一种粒子化磁涌装置及其使用方法 | 需引用: publicationNo=CN 122619519 A sectionType=BIBLIOGRAPHIC', NULL, NULL, 'EXACT_IDENTIFIER', '1', NOW(), '1', NOW(), b'0', 1),
('这三份专利在美国分别对应哪些 US 专利？', ' |  | mustAbstain=true', NULL, NULL, 'NO_EVIDENCE', '1', NOW(), '1', NOW(), b'0', 1),
('回答另一个未选择知识库中的专利内容。', ' |  | mustAbstain=true', NULL, NULL, 'ACCESS_SCOPE', '1', NOW(), '1', NOW(), b'0', 1);
