EDT metadata operations reference:

Catalog: create_metadata(kind=Catalog) then add_metadata_child(child_kind=Attribute|TabularSection). Reserved names: Код, Наименование, Ссылка, ПометкаУдаления, Родитель, Владелец.
Document: create_metadata(kind=Document). Reserved: Номер, Дата, Проведён. For posting: ensure_module_artifact then edit ОбработкаПроведения in object module.
AccumulationRegister: create_metadata(kind=AccumulationRegister). Children: add_metadata_child(child_kind=Dimension|Resource|Attribute). Reserved: Период, Регистратор, Активность.
InformationRegister: create_metadata(kind=InformationRegister). Set periodicity via update_metadata.
Form: create_form(owner_fqn=...) or apply_form_recipe(mode=create). Inspect: inspect_form_layout. Mutate: mutate_form_model.
DCS: dcs_manage(command=create_schema) then dcs_manage(command=upsert_dataset|upsert_param|upsert_field).
Module: ALWAYS ensure_module_artifact before edit_file for BSL modules.
Validation: edt_validate_request -> get validation_token -> pass to mutation tool -> get_diagnostics.