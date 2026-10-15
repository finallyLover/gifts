<template>
  <!-- 礼簿档案编辑对话框：新增/编辑共用 -->
  <el-dialog
    :model-value="modelValue"
    :title="form.id ? '编辑礼簿档案' : '新建礼簿档案'"
    width="520px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => !v && emit('update:modelValue', v)"
    @close="emit('update:modelValue', false)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
      <el-form-item label="档案名称" prop="name">
        <el-input v-model="form.name" placeholder="如：张三婚礼礼簿" maxlength="50" />
      </el-form-item>
      <el-form-item label="宴席类型" prop="banquet_type">
        <el-select
          v-model="form.banquet_type"
          placeholder="选择类型"
          allow-create
          filterable
          style="width: 100%"
        >
          <el-option
            v-for="t in banquetTypes"
            :key="t"
            :label="t"
            :value="t"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="举办日期">
        <el-date-picker
          v-model="form.event_date"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="主人姓名">
        <el-input v-model="form.host_name" maxlength="20" />
      </el-form-item>
      <el-form-item label="联系方式">
        <el-input v-model="form.host_contact" maxlength="30" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import type { Archive, ArchiveInput } from '@/types'
import { createArchive, updateArchive } from '@/api/archive'

const props = defineProps<{
  modelValue: boolean
  archive?: Archive | null
}>()
const emit = defineEmits<{
  'update:modelValue': [boolean]
  saved: [Archive]
}>()

/// 宴席类型常量
const banquetTypes = ['婚礼', '寿宴', '乔迁', '满月', '升学', '开业', '其他']

const formRef = ref<FormInstance>()
const saving = ref(false)

/// 空表单
function emptyForm(): ArchiveInput {
  return {
    id: undefined,
    name: '',
    banquet_type: '婚礼',
    event_date: null,
    host_name: null,
    host_contact: null,
    remark: null
  }
}
const form = reactive<ArchiveInput>(emptyForm())

const rules: FormRules = {
  name: [{ required: true, message: '请输入档案名称', trigger: 'blur' }],
  banquet_type: [{ required: true, message: '请选择宴席类型', trigger: 'change' }]
}

// 打开时回填编辑数据
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      Object.assign(form, props.archive ? { ...props.archive } : emptyForm())
    }
  }
)

async function onSubmit() {
  await formRef.value?.validate()
  saving.value = true
  try {
    const saved = form.id
      ? await updateArchive({ ...form })
      : await createArchive({ ...form })
    ElMessage.success('保存成功')
    emit('saved', saved)
    emit('update:modelValue', false)
  } finally {
    saving.value = false
  }
}
</script>
