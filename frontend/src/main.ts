import { createApp } from "vue";
import {
  ElDialog,
  ElDrawer,
  ElDropdown,
  ElDropdownMenu,
  ElDropdownItem,
  ElInputNumber,
} from "element-plus";
import "element-plus/dist/index.css";
import App from "./App.vue";
import "./style.css";
const app = createApp(App);
for (const component of [
  ElDialog,
  ElDrawer,
  ElDropdown,
  ElDropdownMenu,
  ElDropdownItem,
  ElInputNumber,
])
  app.component(component.name!, component);
app.mount("#app");
