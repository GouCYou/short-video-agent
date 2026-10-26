import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { describe, it, expect } from "vitest";
import ChoiceCards from "../components/ChoiceCards.vue";
import { choiceGroup } from "./conversation-fixtures";
import type { ChoiceGroup } from "../lib/conversation-types";
describe("canonical conversation choices", () => {
  for (const kind of [
    "TOPIC",
    "DIRECTION",
    "AUDIENCE",
    "STYLE",
    "PRODUCTION",
  ] as ChoiceGroup["kind"][]) {
    it(`submits persisted option ids and custom text for ${kind}`, async () => {
      const group = choiceGroup(kind),
        wrapper = mount(ChoiceCards, {
          props: { group },
          global: { plugins: [ElementPlus] },
        });
      expect(wrapper.findAll(".choice-option")).toHaveLength(4);
      await wrapper.findAll(".choice-option")[1]!.trigger("click");
      expect(wrapper.emitted("select")?.[0]).toEqual([
        { groupId: group.id, optionId: `${kind}-b` },
      ]);
      await wrapper.get(".custom-option").trigger("click");
      expect(
        wrapper.get('button[type="submit"]').attributes("disabled"),
      ).toBeDefined();
      await wrapper.get("textarea").setValue("我想面向大三学生");
      await wrapper.get("form").trigger("submit");
      expect(wrapper.emitted("select")?.[1]).toEqual([
        { groupId: group.id, customText: "我想面向大三学生" },
      ]);
      wrapper.unmount();
    });
  }
  it("keeps selected cards immutable and disables superseded or active-run groups", async () => {
    const group = choiceGroup();
    group.status = "SELECTED";
    group.selectedOptionId = group.options[0]!.id;
    const wrapper = mount(ChoiceCards, {
      props: { group },
      global: { plugins: [ElementPlus] },
    });
    expect(
      wrapper.find(".choice-option.selected").attributes("aria-pressed"),
    ).toBe("true");
    expect(
      wrapper
        .findAll(".choice-option")
        .every((button) => button.attributes("disabled") !== undefined),
    ).toBe(true);
    await wrapper.findAll(".choice-option")[1]!.trigger("click");
    expect(wrapper.emitted("select")).toBeUndefined();
    await wrapper.setProps({
      group: { ...choiceGroup(), status: "SUPERSEDED" },
    });
    expect(wrapper.classes()).toContain("superseded");
    expect(
      wrapper
        .findAll(".choice-option")
        .every((button) => button.attributes("disabled") !== undefined),
    ).toBe(true);
    await wrapper.setProps({ group: choiceGroup(), disabled: true });
    expect(
      wrapper
        .findAll(".choice-option")
        .every((button) => button.attributes("disabled") !== undefined),
    ).toBe(true);
    wrapper.unmount();
  });
});
