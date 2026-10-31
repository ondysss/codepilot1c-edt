# Formal managed-form parameters

`inspect_form_layout` reports the formal input parameters in
`formProperties.parameters`, including names, types, qualifiers, key flags and comments.
These are distinct from form data attributes and visual controls.

`mutate_form_model` and `apply_form_recipe.layout` accept:

```json
[
  {"op":"add_parameter","name":"Conversation","type":"String(36)","keyParameter":true},
  {"op":"set_parameter","name":"Conversation","set":{"comment":"Saved conversation identifier"}},
  {"op":"remove_parameter","name":"Conversation"}
]
```

Types are optional on creation; an omitted type allows an unconstrained parameter.
Provided types use the existing EDT type resolver, including qualified and composite
types. An empty type is rejected. `set_parameter` changes only supplied properties;
it does not rename parameters. Names resolve without case sensitivity. Duplicate
creation and a missing update/removal target are errors. Unknown properties and
non-boolean key flags are rejected. The existing validation-token and support-lock
gates apply, and all operations in a batch share one BM write transaction.

After mutation, inspect the form again and verify its serialized model. A successful
tool response alone does not prove runtime behavior. Batch rollback and actual type
resolution must also be exercised in a running EDT.
