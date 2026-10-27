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
      expect(wrapper.findAll(".choice-option")).toHaveLength(3);
      await wrapper.findAll(".choice-option")[1]!.trigger("click");
      expect(wrapper.emitted("select")?.[0]).toEqual([
        { groupId: group.id, optionId: `${kind}-b` },
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

it('renders exactly the supplied count and treats D as a real fourth option',async()=>{
 const base=choiceGroup();
 for(const count of [2,4,5]){
  const group={...base,options:Array.from({length:count},(_,index)=>({id:'option-'+index,label:'ABCDE'[index] as 'A'|'B'|'C'|'D'|'E',title:'选项'+index,description:'说明'.repeat(400),value:'内容'+index}))};
  const wrapper=mount(ChoiceCards,{props:{group},global:{plugins:[ElementPlus]}});
  expect(wrapper.findAll('.choice-row')).toHaveLength(count);expect(wrapper.find('.custom-option').exists()).toBe(false);expect(wrapper.findAll('.choice-details-button')).toHaveLength(count);
  if(count>=4){await wrapper.findAll('.choice-option')[3]!.trigger('click');expect(wrapper.emitted('select')![0]).toEqual([{groupId:group.id,optionId:'option-3'}]);}
  wrapper.unmount();
 }
});
