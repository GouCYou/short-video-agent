import { mount } from "@vue/test-utils";
import { describe, it, expect } from "vitest";
import ChatMessage from "../components/ChatMessage.vue";
import AnimatedDisclosure from "../components/AnimatedDisclosure.vue";
describe("conversation content and expansion", () => {
  it("formats model replies while escaping HTML, blocking unsafe links and remote images", () => {
    const wrapper = mount(ChatMessage, {
      props: {
        content:
          "**重点**\n\n- 步骤一\n- 步骤二\n\n<img src=x onerror=alert(1)>\n\n[坏链接](javascript:alert(1))\n\n[来源](https://example.com/info)\n\n![远程图片](https://example.com/image.png)",
      },
    });
    expect(wrapper.get("strong").text()).toBe("重点");
    expect(wrapper.findAll("li")).toHaveLength(2);
    expect(wrapper.find("img").exists()).toBe(false);
    expect(wrapper.text()).toContain("<img src=x onerror=alert(1)>");
    expect(wrapper.findAll("a")).toHaveLength(1);
    expect(wrapper.get("a").attributes("rel")).toBe("noopener noreferrer");
    expect(wrapper.get("a").attributes("href")).toBe(
      "https://example.com/info",
    );
  });
  it("keeps collapsed content inert and expands with an accessible button", async () => {
    const wrapper = mount(AnimatedDisclosure, {
      slots: { summary: "镜头一", default: "<button>编辑镜头</button>" },
    });
    const toggle = wrapper.get(".disclosure-toggle");
    expect(toggle.attributes("aria-expanded")).toBe("false");
    expect(wrapper.get(".disclosure-motion").attributes("inert")).toBeDefined();
    await toggle.trigger("click");
    expect(toggle.attributes("aria-expanded")).toBe("true");
    expect(
      wrapper.get(".disclosure-motion").attributes("inert"),
    ).toBeUndefined();
    await wrapper.setProps({ open: true });
    expect(toggle.attributes("aria-expanded")).toBe("true");
  });
});
