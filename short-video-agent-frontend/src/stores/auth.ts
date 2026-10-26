import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { api, TOKEN_KEY } from "../lib/api";
import type { User } from "../lib/types";
export const useAuth = defineStore("auth", () => {
  const user = ref<User | null>(null);
  const ready = ref(false);
  const loggedIn = computed(() => !!user.value);
  const admin = computed(() => user.value?.role === "ADMIN");
  async function load() {
    if (!localStorage.getItem(TOKEN_KEY)) {
      ready.value = true;
      return;
    }
    try {
      user.value = await api.me();
    } finally {
      ready.value = true;
    }
  }
  function accept(session: { token: string; user: User }) {
    localStorage.setItem(TOKEN_KEY, session.token);
    user.value = session.user;
    ready.value = true;
  }
  function clear() {
    localStorage.removeItem(TOKEN_KEY);
    user.value = null;
    ready.value = true;
  }
  async function logout() {
    try {
      await api.logout();
    } finally {
      clear();
    }
  }
  return { user, ready, loggedIn, admin, load, accept, clear, logout };
});
