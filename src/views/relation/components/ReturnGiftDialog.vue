<template>
  <!-- 回礼记录新增/编辑对话框（我随别人的礼） -->
  <el-dialog
    :model-value="modelValue"
    :title="form.id ? '编辑回礼记录' : '新增回礼记录'"
    width="480px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => !v && emit('update:modelValue', v)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
      <el-form-item label="对方姓名" prop="person_name">
        <el-input
          v-model="form.person_name"
          :disabled="!!lockName || !!form.id"
          placeholder="对方（来宾）姓名"
        />
      </el-form-item>
      <el-form-item label="宴席名称">
        <el-input v-model="form.event_name" placeholder="如：王某某婚礼" />
      </el-form-item>
      <el-form-item label="宴席时间">
        <el-date-picker
          v-model="form.event_date"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="随礼金额" prop="amount">
        <el-input-number v-model="form.amount" :min="0" :precision="2" style="width: 100%" />
      </el-form-item>
      <el-form-item label="礼品金额">
        <el-input-number v-model="form.gift_amount" :min="0" :precision="2" style="width: 100%" />
        <div class="field-tip">填了表示送了价值该金额的礼品（计入统计）；不填则为实物礼品</div>
      </el-form-item>
      <el-form-item label="礼品">
        <el-input v-model="form.gift" placeholder="如有礼品请填写" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" />
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
import type { ReturnGift, ReturnGiftInput } from '@/types'
import { addReturnGift, updateReturnGift } from '@/api/person'

const props = defineProps<{
  modelValue: boolean
  /// 固定姓名（从某人员详情打开时）
  lockName?: string
  /// 编辑目标
  target?: ReturnGift | null
}>()
const emit = defineEmits<{
  'update:modelValue': [boolean]
  saved: []
}>()

const formRef = ref<FormInstance>()
const saving = ref(false)

/// 空表单
function emptyForm(): ReturnGiftInput {
  return {
    id: undefined,
    person_name: props.lockName ?? '',
    event_name: null,
    event_date: null,
    amount: 0,
    gift: null,
    gift_amount: 0,
    remark: null,
    operator: null
  }
}
const form = reactive<ReturnGiftInput>(emptyForm())

const rules: FormRules = {
  person_name: [{ required: true, message: '请输入对方姓名', trigger: 'blur' }],
  amount: [{ required: true, message: '请输入金额', trigger: 'blur' }]
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    if (props.target) {
      Object.assign(form, {
        id: props.target.id,
        person_name: props.target.person_name,
        event_name: props.target.event_name,
        event_date: props.target.event_date,
        amount: props.target.amount,
        gift: props.target.gift,
        gift_amount: props.target.gift_amount ?? 0,
        remark: props.target.remark,
        operator: props.target.operator
      })
    } else {
      Object.assign(form, emptyForm())
    }
  }
)

async function onSubmit() {
  await formRef.value?.validate()
  if (form.amount < 0 || (form.gift_amount ?? 0) < 0) {
    ElMessage.error('金额与礼品金额均不能为负数')
    return
  }
  saving.value = true
  try {
    if (form.id) await updateReturnGift({ ...form })
    else await addReturnGift({ ...form })
    ElMessage.success('保存成功')
    emit('saved')
    emit('update:modelValue', false)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.field-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  margin-top: 2px;
}
</style>
