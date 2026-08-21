import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';

import { getKnowledgeBasePage } from '#/api/ai/knowledge';

/** 列表搜索 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'kbId',
      label: '知识库',
      component: 'ApiSelect',
      componentProps: {
        api: () => getKnowledgeBasePage({ pageNo: 1, pageSize: 100 }),
        labelField: 'name',
        valueField: 'id',
        resultField: 'list',
        placeholder: '请选择知识库',
        allowClear: true,
      },
    },
    {
      fieldName: 'name',
      label: '文档名',
      component: 'Input',
      componentProps: {
        placeholder: '请输入文档名',
        clearable: true,
      },
    },
    {
      fieldName: 'parseStatus',
      label: '解析状态',
      component: 'Select',
      componentProps: {
        placeholder: '请选择解析状态',
        allowClear: true,
        options: [
          { label: '待解析', value: 'PENDING' },
          { label: '解析中', value: 'PARSING' },
          { label: '向量化中', value: 'EMBEDDING' },
          { label: '已入库', value: 'INDEXED' },
          { label: '失败', value: 'FAILED' },
        ],
      },
    },
  ];
}

/** 列表列 */
export function useGridColumns(): VxeTableGridOptions['columns'] {
  return [
    { field: 'id', title: '编号', width: 80 },
    {
      field: 'name',
      title: '文档名',
      minWidth: 220,
      showOverflow: true,
    },
    {
      field: 'type',
      title: '类型',
      width: 90,
      slots: { default: 'type' },
    },
    {
      field: 'parseStatus',
      title: '解析状态',
      width: 130,
      slots: { default: 'status' },
    },
    {
      field: 'chunkCount',
      title: '片段数',
      width: 90,
      align: 'center',
      slots: { default: 'chunkCount' },
    },
    {
      field: 'owner',
      title: '上传人',
      width: 110,
    },
    {
      field: 'createTime',
      title: '上传时间',
      width: 170,
    },
    {
      field: 'operation',
      title: '操作',
      width: 100,
      slots: { default: 'operation' },
      fixed: 'right',
    },
  ];
}
