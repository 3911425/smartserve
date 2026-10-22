<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { api } from "../api";
import type { Entity } from "../format";
const props = defineProps<{ modelValue: boolean; user: Entity }>();
const emit = defineEmits(["update:modelValue", "updated", "password-changed"]);
const form = reactive({ displayName: "", phone: "", shippingAddress: "" });
const passwords = reactive({
  currentPassword: "",
  newPassword: "",
  confirmation: "",
});
const busy = ref(false),
  section = ref("profile");
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      Object.assign(form, {
        displayName: props.user.displayName,
        phone: props.user.phone || "",
        shippingAddress: props.user.shippingAddress || "",
      });
      Object.assign(passwords, {
        currentPassword: "",
        newPassword: "",
        confirmation: "",
      });
      section.value = "profile";
    }
  },
);
async function save() {
  busy.value = true;
  try {
    emit("updated", await api("/auth/profile", "PUT", form));
    ElMessage.success("个人资料已更新");
    emit("update:modelValue", false);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
async function changePassword() {
  if (passwords.newPassword !== passwords.confirmation)
    return ElMessage.warning("两次新密码不一致");
  busy.value = true;
  try {
    await api("/auth/password", "PUT", {
      currentPassword: passwords.currentPassword,
      newPassword: passwords.newPassword,
    });
    ElMessage.success("密码已修改，请重新登录");
    emit("password-changed");
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    title="我的账号"
    width="min(520px,92vw)"
    @update:model-value="emit('update:modelValue', $event)"
    ><div class="segmented">
      <button
        :class="{ active: section === 'profile' }"
        @click="section = 'profile'"
      >
        个人资料</button
      ><button
        :class="{ active: section === 'password' }"
        @click="section = 'password'"
      >
        修改密码
      </button>
    </div>
    <form
      v-if="section === 'profile'"
      class="form-stack"
      @submit.prevent="save"
    >
      <label>账号<input :value="user.username" disabled /></label
      ><label
        >昵称<input v-model="form.displayName" maxlength="60" required /></label
      ><label
        >联系电话<input
          v-model="form.phone"
          maxlength="30"
          placeholder="用于收货信息默认值" /></label
      ><label
        >默认收货地址<textarea
          v-model="form.shippingAddress"
          maxlength="300"
          rows="3"
          placeholder="购买时仍可核对修改"
        ></textarea></label
      ><button class="primary" :disabled="busy">保存资料</button>
    </form>
    <form v-else class="form-stack" @submit.prevent="changePassword">
      <label
        >当前密码<input
          v-model="passwords.currentPassword"
          type="password"
          autocomplete="current-password"
          required /></label
      ><label
        >新密码<input
          v-model="passwords.newPassword"
          type="password"
          minlength="8"
          maxlength="72"
          autocomplete="new-password"
          required /></label
      ><label
        >确认新密码<input
          v-model="passwords.confirmation"
          type="password"
          minlength="8"
          maxlength="72"
          autocomplete="new-password"
          required
      /></label>
      <p class="muted">修改后，所有设备上的已有登录会话都会失效。</p>
      <button class="primary" :disabled="busy">修改并重新登录</button>
    </form></el-dialog
  >
</template>
