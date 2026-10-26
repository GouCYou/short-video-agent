import { mount, flushPromises } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { it, expect, vi } from "vitest";
import KnowledgeImport from "../components/KnowledgeImport.vue";
import { api } from "../lib/api";
it("imports multiple files once and distinguishes saved duplicates from failed files", async () => {
  const send = vi.spyOn(api, "importKnowledge").mockResolvedValue({
    indexingAvailable: false,
    items: [
      { documentId: "first", name: "拍摄方法.docx", status: "UNINDEXED" },
      {
        documentId: "first",
        name: "重复资料.pdf",
        status: "UNINDEXED",
        duplicate: true,
      },
      { name: "损坏资料.pdf", status: "FAILED", error: "无法解析文件" },
    ],
  });
  const wrapper = mount(KnowledgeImport, {
    props: { modelValue: true },
    global: { plugins: [ElementPlus], stubs: { teleport: true } },
  });
  await flushPromises();
  const input = wrapper.get('input[type="file"]'),
    files = [
      new File(["one"], "拍摄方法.docx"),
      new File(["two"], "重复资料.pdf"),
      new File(["broken"], "损坏资料.pdf"),
    ];
  Object.defineProperty(input.element, "files", {
    value: files,
    configurable: true,
  });
  await input.trigger("change");
  expect(wrapper.findAll(".knowledge-import-file")).toHaveLength(3);
  const button = wrapper
    .findAll("button")
    .find((b) => b.text() === "一键导入")!;
  await button.trigger("click");
  await flushPromises();
  expect(send).toHaveBeenCalledTimes(1);
  expect(send.mock.calls[0]![0].getAll("files")).toHaveLength(3);
  expect(wrapper.text()).toContain("2 份已保存");
  expect(wrapper.text()).toContain("未重复导入");
  expect(wrapper.text()).toContain("无法解析文件");
  expect(wrapper.text()).toContain("配置向量模型后可建立索引");
  expect(wrapper.emitted("imported")).toHaveLength(1);
  wrapper.unmount();
  send.mockRestore();
});

import AnimatedDisclosure from "../components/AnimatedDisclosure.vue";
it("expands chunk settings without submitting the surrounding edit form", async () => {
  const submit = vi.fn((e: Event) => e.preventDefault());
  const wrapper = mount({
    components: { AnimatedDisclosure },
    template:
      '<form @submit="submit"><AnimatedDisclosure><template #summary>分块设置</template><input value="未保存的正文" /></AnimatedDisclosure></form>',
    setup: () => ({ submit }),
  });
  const button = wrapper.get("button");
  expect(button.attributes("type")).toBe("button");
  (button.element as HTMLButtonElement).click();
  await flushPromises();
  expect(submit).not.toHaveBeenCalled();
  expect(wrapper.get("input").element.value).toBe("未保存的正文");
  expect(button.attributes("aria-expanded")).toBe("true");
  wrapper.unmount();
});
