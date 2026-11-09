"""Generate synthetic protocol fixtures with the official deepseek-recipe 0.2.0 binding."""
import json
import sys
from pathlib import Path

from deepseek_recipe import ChatCompletionRequest, ConversionOptions, DeepseekV41Encoding, Tokenizer

encoder = DeepseekV41Encoding().with_tokenizer(Tokenizer.from_str(Path(sys.argv[1]).read_text()))
tool = {"type": "function", "function": {"name": "inspect", "description": "Read a file",
        "parameters": {"type": "object", "properties": {"path": {"type": "string"}}}}}
messages = [{"role": "system", "content": "Authorized step"}, {"role": "user", "content": "Inspect files"}]
for index in range(2):
    calls = [{"type": "function", "id": f"{index}-{path}",
              "function": {"name": "inspect", "arguments": json.dumps({"path": path})}}
             for path in ("a", "b")]
    messages.append({"role": "assistant", "content": "" if index == 0 else "Checking",
                     "reasoning_content": f"Synthetic fixture {index} <｜ref｜> <think>",
                     "tool_calls": calls})
    messages.extend({"role": "tool", "content": "OK", "tool_call_id": call["id"]} for call in calls)
messages.append({"role": "user", "content": "Current notes"})
fixtures = []
for name, effort, history, tools in [
    ("tools and notes", "high", messages, [tool]),
    ("tools and notes", "max", messages, [tool]),
    ("no tools", "low", messages[:2], []),
    ("no system", "high", messages[1:2], []),
    ("prior reasoning ignored without tools", "max", [
        messages[0], messages[1], {"role": "assistant", "content": "OK", "reasoning_content": "fixture"},
        messages[-1]], []),
]:
    request = ChatCompletionRequest(dict(model="DeepSeek-V4.1-Flash", messages=history, tools=tools,
                                         thinking={"type": "enabled"}, reasoning_effort=effort))
    conversation = request.convert(ConversionOptions()).conversation
    fixtures.append(dict(name=name, effort=effort, messages=history, tools=tools,
                         tokens=len(encoder.encode(conversation))))
print(json.dumps(fixtures, ensure_ascii=False, indent=2))
