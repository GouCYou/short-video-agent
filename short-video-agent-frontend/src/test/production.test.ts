import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { describe, it, expect } from "vitest";
import ProductionDocument from "../components/ProductionDocument.vue";
import { production } from "./conversation-fixtures";
describe("complete production artifact", () => {
  it("exposes all actionable production sections with safe plain-text output", () => {
    const value = structuredClone(production);
    value.segments[0]!.visual = "<img src=x onerror=alert(1)>";
    const wrapper = mount(ProductionDocument, {
      props: { modelValue: value },
      global: { plugins: [ElementPlus] },
    });
    expect(wrapper.text()).toContain("手机实拍");
    expect(wrapper.text()).toContain("后期录制口播");
    expect(wrapper.text()).toContain("关闭空调");
    expect(wrapper.text()).toContain("叙事剪辑");
    expect(wrapper.text()).toContain("授权轻音乐");
    expect(wrapper.text()).toContain("图书馆外景");
    expect(wrapper.text()).toContain(value.segments[0]!.visual);
    expect(wrapper.find("img").exists()).toBe(false);
    expect(
      wrapper
        .get(".quality-notes .disclosure-toggle")
        .attributes("aria-expanded"),
    ).toBe("false");
    wrapper.unmount();
  });
  it("preserves stable segment identity and unaffected blocks when editing one field", async () => {
    const value = structuredClone(production),
      original = structuredClone(value);
    const wrapper = mount(ProductionDocument, {
      props: { modelValue: value, editable: true },
      global: { plugins: [ElementPlus] },
    });
    const narration = wrapper.findAll('textarea[aria-label="口播"]')[0]!;
    await narration.setValue("局部修改后的配音");
    expect(value.segments[0]!.id).toBe("shot-stable-one");
    expect(value.segments[0]!.narration).toBe("局部修改后的配音");
    expect(value.shootingPlan).toEqual(original.shootingPlan);
    expect(value.editingPlan).toEqual(original.editingPlan);
    const button = wrapper
      .findAll("button")
      .find((item) => item.text() === "添加镜头")!;
    await button.trigger("click");
    expect(value.segments).toHaveLength(2);
    expect(value.segments[1]!.id).not.toBe(value.segments[0]!.id);
    wrapper.unmount();
  });
});
