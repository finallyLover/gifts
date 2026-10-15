<template>
  <!-- 原图查看器：缩放 / 平移 / 旋转（纯查看，不做表格分割） -->
  <div class="canvas-shell">
    <!-- 画布工具栏 -->
    <div class="canvas-toolbar">
      <el-button-group>
        <el-button :icon="ZoomOut" @click="zoomStep(-0.1)" />
        <el-button @click="zoom = 1">
          {{ Math.round(zoom * 100) }}%
        </el-button>
        <el-button :icon="ZoomIn" @click="zoomStep(0.1)" />
      </el-button-group>
      <el-button :icon="FullScreen" title="适应窗口" @click="fitWindow" />
      <template v-if="!readonly">
        <el-divider direction="vertical" />
        <el-button :icon="RefreshLeft" title="向左旋转90°" @click="emit('rotate', -90)" />
        <el-button :icon="RefreshRight" title="向右旋转90°" @click="emit('rotate', 90)" />
      </template>
      <span class="tip-text">提示：滚轮缩放，按住拖拽平移</span>
    </div>

    <!-- 视口（滚轮缩放 / 拖拽平移） -->
    <div
      ref="viewportRef"
      class="canvas-viewport"
      @wheel.prevent="onWheel"
      @pointerdown="startPan"
    >
      <div
        class="canvas-stage"
        :style="{
          width: naturalW + 'px',
          height: naturalH + 'px',
          transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`
        }"
      >
        <img
          ref="imgRef"
          :src="src"
          class="canvas-img"
          draggable="false"
          @load="onImgLoad"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { ZoomIn, ZoomOut, FullScreen, RefreshLeft, RefreshRight } from '@element-plus/icons-vue'

defineProps<{
  src: string
  /** 只读查看模式：隐藏旋转等会修改文件的操作按钮 */
  readonly?: boolean
}>()
const emit = defineEmits<{
  'rotate': [number]
}>()

// ---------- 图片自然尺寸 ----------
const imgRef = ref<HTMLImageElement>()
const naturalW = ref(100)
const naturalH = ref(100)
function onImgLoad() {
  naturalW.value = imgRef.value?.naturalWidth ?? 100
  naturalH.value = imgRef.value?.naturalHeight ?? 100
  nextTick(fitWindow)
}

// ---------- 缩放 / 平移 ----------
const zoom = ref(1)
const pan = ref({ x: 40, y: 20 })
const viewportRef = ref<HTMLDivElement>()

function zoomStep(delta: number) {
  zoom.value = Math.min(Math.max(zoom.value + delta, 0.1), 6)
}
function onWheel(e: WheelEvent) {
  zoomStep(e.deltaY < 0 ? 0.1 : -0.1)
}
/// 适应窗口
function fitWindow() {
  const el = viewportRef.value
  if (!el || !naturalW.value) return
  const z = Math.min(
    (el.clientWidth - 60) / naturalW.value,
    (el.clientHeight - 60) / naturalH.value,
    2
  )
  zoom.value = z
  pan.value = {
    x: (el.clientWidth - naturalW.value * z) / 2,
    y: (el.clientHeight - naturalH.value * z) / 2
  }
}

/// 按下拖拽 → 平移
function startPan(e: PointerEvent) {
  const start = { x: e.clientX, y: e.clientY, base: { ...pan.value } }
  const move = (ev: PointerEvent) => {
    pan.value = {
      x: start.base.x + ev.clientX - start.x,
      y: start.base.y + ev.clientY - start.y
    }
  }
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

/// 暴露自然尺寸（父组件兜底逻辑可能使用）
defineExpose({
  naturalW,
  naturalH
})
</script>

<style scoped>
.canvas-shell {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #e9ebef;
}
.canvas-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.tip-text {
  color: #909399;
  font-size: 12px;
  margin-left: 8px;
}
.canvas-viewport {
  flex: 1;
  overflow: hidden;
  position: relative;
  cursor: grab;
}
.canvas-stage {
  position: absolute;
  left: 0;
  top: 0;
  transform-origin: 0 0;
}
.canvas-img {
  width: 100%;
  height: 100%;
  display: block;
  box-shadow: 0 2px 14px rgba(0, 0, 0, 0.25);
  pointer-events: none;
}
</style>
