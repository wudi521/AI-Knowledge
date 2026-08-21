import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';

/** 新增/编辑 表单 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'id',
      component: 'Input',
      formItemClass: 'hidden',
    },
    {
      fieldName: 'name',
      label: '名称',
      component: 'Input',
      componentProps: {
        placeholder: '请输入知识库名称',
        clearable: true,
      },
      rules: 'required',
    },
    {
      fieldName: 'chunkStrategy',
      label: '切分策略',
      component: 'Select',
      componentProps: {
        placeholder: '请选择切分策略',
        options: [
          { label: '语义切分 (Semantic)', value: 'Semantic' },
          { label: '父子切分 (ParentChild)', value: 'ParentChild' },
          { label: '表格切分 (Table)', value: 'Table' },
          { label: '问答切分 (FAQ)', value: 'FAQ' },
          { label: '条款切分 (Policy)', value: 'Policy' },
        ],
      },
      defaultValue: 'ParentChild',
    },
    {
      fieldName: 'embedModel',
      label: 'Embedding 模型',
      component: 'Select',
      componentProps: {
        placeholder: '请选择 Embedding 模型',
        options: [
          { label: 'BGE-M3', value: 'BGE-M3' },
          { label: 'TextEmbedding-3-v2', value: 'TextEmbedding-3-v2' },
        ],
      },
      defaultValue: 'BGE-M3',
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        options: [
          { label: '启用', value: 1 },
          { label: '禁用', value: 0 },
        ],
      },
      defaultValue: 1,
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'Textarea',
      componentProps: {
        placeholder: '请输入备注',
        rows: 3,
      },
    },
  ];
}

/** 列表搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '名称',
      component: 'Input',
      componentProps: {
        placeholder: '请输入知识库名称',
        clearable: true,
      },
    },
  ];
}

/** 列表列 */
export function useGridColumns(): VxeTableGridOptions['columns'] {
  return [
    {
      field: 'id',
      title: '编号',
      width: 80,
    },
    {
      field: 'name',
      title: '名称',
      minWidth: 160,
      showOverflow: true,
    },
    {
      field: 'chunkStrategy',
      title: '切分策略',
      minWidth: 130,
    },
    {
      field: 'embedModel',
      title: 'Embedding 模型',
      minWidth: 140,
    },
    {
      field: 'status',
      title: '状态',
      width: 90,
      slots: { default: 'status' },
    },
    {
      field: 'remark',
      title: '备注',
      minWidth: 160,
      showOverflow: true,
    },
    {
      field: 'createTime',
      title: '创建时间',
      width: 170,
    },
    {
      field: 'operation',
      title: '操作',
      width: 140,
      slots: { default: 'operation' },
      fixed: 'right',
    },
  ];
}
