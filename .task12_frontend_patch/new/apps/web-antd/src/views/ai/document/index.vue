<script lang="ts" setup>
import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';

import type { KnowledgeDocument } from '#/api/ai/knowledge';

import { Page, confirm } from '@vben/common-ui';

import { Button, Tag, Upload, message } from 'ant-design-vue';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';

import {
  createDocument,
  deleteDocument,
  getDocumentPage,
} from '#/api/ai/knowledge';
import { uploadFile } from '#/api/infra/file';

import { useGridColumns, useGridFormSchema } from './data';

function handleRefresh() {
  gridApi.query();
}

/** 文件后缀 → 文档类型 */
function getDocType(name: string): string {
  const ext = (name.split('.').pop() || '').toLowerCase();
  const map: Record<string, string> = {
    md: 'MD',
    pdf: 'PDF',
    doc: 'WORD',
    docx: 'WORD',
    xls: 'EXCEL',
    xlsx: 'EXCEL',
    ppt: 'PPT',
    pptx: 'PPT',
    txt: 'TXT',
  };
  return map[ext] || 'OTHER';
}

/** 上传文档: 先传 MinIO, 再登记 ai_document */
async function handleUploadFile(file: File) {
  // 校验知识库已选择
  const kbId = (await gridApi.formApi.getValues()).kbId;
  if (!kbId) {
    message.warning('请先在搜索区选择知识库');
    return false;
  }
  try {
    message.loading({ content: `上传中: ${file.name}...`, key: 'upload' });
    // 统一走文件服务: { file, directory } 结构
    const url = (await uploadFile({ file, directory: 'kb-docs' } as any)) as unknown as string;
    await createDocument({
      kbId,
      name: file.name,
      type: getDocType(file.name),
      storagePath: url,
    });
    message.success({ content: `「${file.name}」已登记入库(待解析)`, key: 'upload' });
    handleRefresh();
  } catch {
    message.error({ content: `「${file.name}」上传失败`, key: 'upload' });
  }
  return false;
}

/** 删除文档 */
async function handleDelete(row: KnowledgeDocument) {
  await confirm(`确认删除文档「${row.name}」吗？`);
  try {
    await deleteDocument(row.id!);
    message.success('删除成功');
    handleRefresh();
  } catch {
    // 取消或失败
  }
}

const STATUS_TAG: Record<string, { color: string; text: string }> = {
  PENDING: { color: 'default', text: '待解析' },
  PARSING: { color: 'processing', text: '解析中' },
  EMBEDDING: { color: 'warning', text: '向量化中' },
  INDEXED: { color: 'success', text: '已入库' },
  FAILED: { color: 'error', text: '失败' },
};

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema() as VbenFormSchema[],
  },
  gridOptions: {
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getDocumentPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: {
      keyField: 'id',
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: true,
    },
  } as VxeTableGridOptions<KnowledgeDocument>,
});
</script>

<template>
  <Page auto-content-height>
    <Grid table-title="入库管线 · 文档列表">
      <template #toolbar-tools>
        <Upload
          :show-upload-list="false"
          :before-upload="handleUploadFile"
          :multiple="false"
        >
          <Button type="primary" :icon="ACTION_ICON.UPLOAD">上传文档</Button>
        </Upload>
      </template>
      <template #type="{ row }">
        <Tag>{{ row.type }}</Tag>
      </template>
      <template #status="{ row }">
        <Tag :color="(row.parseStatus && STATUS_TAG[row.parseStatus]?.color) || 'default'">
          {{ (row.parseStatus && STATUS_TAG[row.parseStatus]?.text) || row.parseStatus }}
        </Tag>
        <a-tooltip v-if="row.parseStatus === 'FAILED' && row.errorMsg" :title="row.errorMsg">
          <span class="ml-1 cursor-help text-xs text-red-500">ⓘ</span>
        </a-tooltip>
      </template>
      <template #chunkCount="{ row }">
        <span v-if="row.parseStatus === 'INDEXED'">{{ row.chunkCount ?? '-' }}</span>
        <span v-else class="text-gray-400">-</span>
      </template>
      <template #operation="{ row }">
        <TableAction
          :actions="[
            {
              label: '删除',
              icon: ACTION_ICON.DELETE,
              onClick: () => handleDelete(row),
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
